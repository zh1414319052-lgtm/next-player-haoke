"""Read-only synthetic FTP fixture for the QA playback test. Never point it at user media.

Requires pyftpdlib. Logs only fixture offsets/events. Binds one explicit LAN address.
The first active transfer is interrupted at 120 seconds; subsequent transfers are healthy.
"""
import argparse
import json
import time
from pathlib import Path

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler, ThrottledDTPHandler
from pyftpdlib.servers import FTPServer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=21214)
    args = parser.parse_args()
    root = Path(args.root).resolve()
    if not (root / "ftp-synthetic.mp4").is_file():
        raise SystemExit("Missing synthetic fixture; refusing to serve an arbitrary directory")

    def event(kind, **fields):
        print(json.dumps({"event": kind, "time": round(time.monotonic(), 2), **fields}), flush=True)

    class Data(ThrottledDTPHandler):
        write_limit = 256 * 1024
        timeout = 30

    class Handler(FTPHandler):
        dtp_handler = Data
        timeout = 45
        passive_ports = range(21215, 21225)
        armed = False

        def ftp_RETR(self, file):
            event("RETR", offset=self._restart_position)
            result = super().ftp_RETR(file)
            if result and not Handler.armed:
                Handler.armed = True

                def interrupt():
                    if self.connected and self.data_channel is not None:
                        event("INJECT_DISCONNECT", sent=self.data_channel.tot_bytes_sent)
                        self.close()
                    else:
                        event("INJECTION_MISSED")

                self.ioloop.call_later(120, interrupt)
            return result

        def on_file_sent(self, file):
            event("COMPLETE")

        def on_incomplete_file_sent(self, file):
            event("INCOMPLETE")

    authorizer = DummyAuthorizer()
    authorizer.add_anonymous(str(root), perm="elr")
    Handler.authorizer = authorizer
    server = FTPServer((args.host, args.port), Handler)
    server.max_cons = 10
    event("READY", host=args.host, port=args.port)
    try:
        server.serve_forever(timeout=0.5)
    finally:
        server.close_all()


if __name__ == "__main__":
    main()
