"""Deliver one committed RoomBook outbox event over authenticated SMTP/TLS."""
import json
import os
import smtplib
import ssl
import sys
from email.message import EmailMessage

event = json.load(sys.stdin.buffer)
message = EmailMessage()
message["From"] = os.environ["ROOMBOOK_SMTP_FROM"]
message["To"] = event["to"]
message["Subject"] = "RoomBook: " + event["subject"]
message["Message-ID"] = "<" + event["id"] + "@roombook.local>"
message.set_content(event["body"])
with smtplib.SMTP_SSL(os.environ["ROOMBOOK_SMTP_HOST"], int(os.getenv("ROOMBOOK_SMTP_PORT", "465")),
                      timeout=8, context=ssl.create_default_context()) as smtp:
    smtp.login(os.environ["ROOMBOOK_SMTP_USER"], os.environ["ROOMBOOK_SMTP_PASSWORD"])
    smtp.send_message(message)
