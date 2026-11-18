"""Register a temporary demo identity, book a room, verify the agenda and cancel."""
import argparse
from datetime import datetime, timedelta, timezone
import json
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import uuid4


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8080)
    base = "http://127.0.0.1:{}/api".format(parser.parse_args().port)
    headers = {}

    def request(path, form=None):
        body = None if form is None else urlencode(form).encode("utf-8")
        with urlopen(Request(base + path, data=body, headers=headers), timeout=10) as response:
            return json.load(response)["data"]

    credentials = {"username": "demo_" + uuid4().hex, "password": uuid4().hex}
    request("/register", credentials)
    headers["Authorization"] = "Bearer " + request("/login", credentials)["token"]
    tomorrow = datetime.now(timezone(timedelta(hours=8))).date() + timedelta(days=1)
    window = {"date": tomorrow.isoformat(), "start": "10:00", "end": "11:00"}
    rooms = request("/availability?" + urlencode(window))
    if not rooms:
        raise RuntimeError("No room available tomorrow at 10:00")
    room = rooms[0]
    booking = request("/bookings", dict(window, room=room["id"], attendees=1))["id"]
    try:
        agenda = request("/my-bookings?" + urlencode({"date": tomorrow.isoformat()}))
        if not any(item["id"] == booking and item["room"] == room["id"] for item in agenda):
            raise RuntimeError("Created booking missing from employee agenda")
        print("Booked {} on {} at 10:00. Booking ID {}".format(room["name"], tomorrow, booking))
    finally:
        request("/bookings/" + booking + "/cancel", {})
    print("Demo booking cancelled.")


if __name__ == "__main__":
    main()
