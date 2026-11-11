import sys
from mail_sender import MailSender

def main():
    if len(sys.argv) < 4:
        print("usage: send_message.py <email> <subject> <content>")
        return 1
    to_email = sys.argv[1]
    subject = sys.argv[2]
    content = sys.argv[3]
    sender = MailSender()
    success, msg = sender.send_mail(to_email, subject, content)
    if not success:
        print(msg)
        return 1
    print("ok")
    return 0

if __name__ == "__main__":
    sys.exit(main())
