import hashlib
import os
import tempfile
import threading
import time
import zipfile

from twisted.internet import defer
from twisted.internet import reactor
from twisted.internet.defer import inlineCallbacks

from wormhole import create
from wormhole.errors import TransferError
from wormhole.transit import TransitReceiver
from wormhole.util import bytes_to_dict, bytes_to_hexstr, dict_to_bytes

APPID = "lothar.com/wormhole/text-or-file-xfer"
MAILBOX_RELAY = "ws://relay.magic-wormhole.io:4000/v1"
TRANSIT_RELAY = "tcp:transit.magic-wormhole.io:4001"

_reactor_ready = threading.Event()
_reactor_thread = None
_active_session = None
_session_lock = threading.Lock()


def _run_reactor():
    reactor.callWhenRunning(_reactor_ready.set)
    reactor.run(installSignalHandlers=False)


def _ensure_reactor():
    global _reactor_thread
    if _reactor_thread is None:
        _reactor_thread = threading.Thread(
            target=_run_reactor,
            name="lion-wormhole-reactor",
            daemon=True,
        )
        _reactor_thread.start()
    _reactor_ready.wait()


def _clear_session(session):
    global _active_session
    with _session_lock:
        if _active_session is session:
            _active_session = None


def start_receive(code, scratch_root, callback):
    global _active_session

    clean_code = code.strip()
    if not clean_code:
        raise ValueError("Enter the code from the sending device.")

    os.makedirs(scratch_root, exist_ok=True)
    _ensure_reactor()

    with _session_lock:
        if _active_session is not None:
            raise RuntimeError("A transfer is already in progress.")
        _active_session = LionReceiverSession(clean_code, scratch_root, callback)
        session = _active_session

    reactor.callFromThread(session.start)


def cancel_receive():
    with _session_lock:
        session = _active_session
    if session is not None:
        reactor.callFromThread(session.cancel)


class LionReceiverSession:
    def __init__(self, code, scratch_root, callback):
        self.code = code
        self.scratch_root = scratch_root
        self.callback = callback
        self.wormhole = None
        self.transfer = None
        self.record_pipe = None
        self.deferred = None
        self.total_bytes = 0
        self.received_bytes = 0
        self.last_reported_bytes = 0
        self.last_report_time = 0.0
        self.temp_path = None
        self.transfer_name = None
        self.transfer_kind = None

    def start(self):
        self.deferred = self._run()
        self.deferred.addCallbacks(self._finished, self._failed)

    def cancel(self):
        if self.deferred is not None:
            self.deferred.cancel()

    def _notify(self, method, *args):
        run = getattr(self.callback, method, None)
        if run is not None:
            try:
                run(*args)
            except Exception:
                pass

    def _status(self, stage, message):
        self._notify("onStatus", stage, message)

    @inlineCallbacks
    def _run(self):
        try:
            yield self._receive()
        finally:
            if self.wormhole is not None:
                try:
                    yield self.wormhole.close()
                except Exception:
                    pass

    @inlineCallbacks
    def _receive(self):
        self._status("connecting", "Connecting to the wormhole relay.")
        self.wormhole = create(APPID, MAILBOX_RELAY, reactor)
        yield self.wormhole.get_welcome()
        self.wormhole.set_code(self.code)
        yield self.wormhole.get_code()

        self._status("waiting", "Waiting for the sender to confirm the code.")
        yield self.wormhole.get_unverified_key()

        self._status("verifying", "Code matched. Securing the transfer.")
        yield self.wormhole.get_verifier()

        awaiting_offer = True
        while True:
            inbound = yield self._receive_message()
            if "transit" in inbound:
                yield self._build_transit(inbound["transit"])

            if "offer" in inbound:
                if not awaiting_offer:
                    raise TransferError("duplicate offer")
                awaiting_offer = False
                yield self._handle_offer(inbound["offer"])
                return

    @inlineCallbacks
    def _receive_message(self):
        body = yield self.wormhole.get_message()
        payload = bytes_to_dict(body)
        if "error" in payload:
            raise TransferError(payload["error"])
        return payload

    def _send_message(self, payload):
        self.wormhole.send_message(dict_to_bytes(payload))

    @inlineCallbacks
    def _build_transit(self, sender_transit):
        if self.transfer is not None:
            return

        self.transfer = TransitReceiver(
            TRANSIT_RELAY,
            no_listen=True,
            reactor=reactor,
        )

        transit_key = self.wormhole.derive_key(
            APPID + "/transit-key",
            self.transfer.TRANSIT_KEY_LENGTH,
        )
        self.transfer.set_transit_key(transit_key)
        self.transfer.add_connection_hints(sender_transit.get("hints-v1", []))

        reply = {
            "transit": {
                "abilities-v1": self.transfer.get_connection_abilities(),
                "hints-v1": (yield self.transfer.get_connection_hints()),
            }
        }
        self._send_message(reply)

    @inlineCallbacks
    def _handle_offer(self, offer):
        if "file" in offer:
            target = self._prepare_file_offer(offer["file"])
            self._send_message({"answer": {"file_ack": "ok"}})
            yield self._receive_payload(target)
            self._finalize_file(target)
            return

        if "directory" in offer:
            target = self._prepare_directory_offer(offer["directory"])
            self._send_message({"answer": {"file_ack": "ok"}})
            yield self._receive_payload(target)
            self._finalize_directory(target)
            return

        raise TransferError("Lion can only receive files or folders.")

    def _prepare_file_offer(self, file_offer):
        filename = os.path.basename(file_offer["filename"])
        self.transfer_name = filename
        self.transfer_kind = "file"
        self.total_bytes = int(file_offer["filesize"])
        self.temp_path = os.path.join(self.scratch_root, filename)
        self._notify("onOffer", "file", filename, self.total_bytes, 1, self.total_bytes)
        return open(self.temp_path + ".part", "wb")

    def _prepare_directory_offer(self, directory_offer):
        dirname = os.path.basename(directory_offer["dirname"])
        self.transfer_name = dirname
        self.transfer_kind = "directory"
        self.total_bytes = int(directory_offer["zipsize"])
        self.temp_path = os.path.join(self.scratch_root, dirname)
        self._notify(
            "onOffer",
            "directory",
            dirname,
            self.total_bytes,
            int(directory_offer["numfiles"]),
            int(directory_offer["numbytes"]),
        )
        archive = tempfile.SpooledTemporaryFile(max_size=10 * 1000 * 1000)
        if not hasattr(archive, "seekable"):
            archive.seekable = lambda: True
        return archive

    @inlineCallbacks
    def _receive_payload(self, file_object):
        if self.transfer is None:
            raise TransferError("Sender did not provide transit details.")

        self._status("connecting", "Negotiating the data path.")
        self.record_pipe = yield self.transfer.connect()
        self._status("receiving", f"Receiving data over {self.record_pipe.describe()}.")

        self.received_bytes = 0
        self.last_reported_bytes = 0
        self.last_report_time = time.monotonic()
        digest = hashlib.sha256()

        def progress(chunk_size):
            self.received_bytes += chunk_size
            now = time.monotonic()
            elapsed = now - self.last_report_time
            should_report = elapsed >= 0.2 or self.received_bytes == self.total_bytes
            if should_report:
                delta = self.received_bytes - self.last_reported_bytes
                speed = delta / elapsed if elapsed > 0 else 0.0
                self._notify(
                    "onProgress",
                    int(self.received_bytes),
                    int(self.total_bytes),
                    float(speed),
                )
                self.last_reported_bytes = self.received_bytes
                self.last_report_time = now

        received = yield self.record_pipe.writeToFile(
            file_object,
            self.total_bytes,
            progress,
            digest.update,
        )

        if received < self.total_bytes:
            raise TransferError("Connection dropped before the full transfer arrived.")

        if self.last_reported_bytes != self.received_bytes:
            self._notify(
                "onProgress",
                int(self.received_bytes),
                int(self.total_bytes),
                0.0,
            )

        yield self._send_ack(digest.digest())

    def _finalize_file(self, file_object):
        temp_part = file_object.name
        file_object.close()
        os.rename(temp_part, self.temp_path)
        self._notify(
            "onComplete",
            self.transfer_kind,
            self.transfer_name,
            self.temp_path,
            self.scratch_root,
            "",
        )

    def _extract_file(self, archive, info, extract_dir):
        target_path = os.path.abspath(os.path.join(extract_dir, info.filename))
        if not target_path.startswith(os.path.abspath(extract_dir)):
            raise TransferError("Refused to unpack a malicious archive entry.")

        archive.extract(info.filename, path=extract_dir)
        os.chmod(target_path, info.external_attr >> 16)

    def _finalize_directory(self, archive_file):
        self._status("receiving", "Unpacking the received folder.")
        with zipfile.ZipFile(archive_file, "r") as archive:
            for info in archive.infolist():
                self._extract_file(archive, info, self.temp_path)
        archive_file.close()
        self._notify(
            "onComplete",
            self.transfer_kind,
            self.transfer_name,
            self.temp_path,
            self.scratch_root,
            "",
        )

    @inlineCallbacks
    def _send_ack(self, datahash):
        if self.record_pipe is None:
            return
        ack = {
            "ack": "ok",
            "sha256": bytes_to_hexstr(datahash),
        }
        yield self.record_pipe.send_record(dict_to_bytes(ack))
        yield self.record_pipe.close()

    def _finished(self, _result):
        _clear_session(self)
        return None

    def _failed(self, failure):
        _clear_session(self)
        if self.record_pipe is not None:
            try:
                self.record_pipe.close()
            except Exception:
                pass

        if failure.check(defer.CancelledError):
            self._notify("onCanceled")
            return None

        message = str(getattr(failure, "value", failure))
        self._notify("onError", message or "Transfer failed.")
        return None
