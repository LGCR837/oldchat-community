from mail_sender import MailSender
import sys

def main():
    sender = MailSender()
    target = sys.argv[1] if len(sys.argv) > 1 else "example@qq.com"
    subject = "Hello from OldChat"
    content = "你好！这是一条由 AI 代理通过 Python 自动发送的测试邮件。"
    
    print(f"尝试发送邮件至 {target}...")
    success, message = sender.send_mail(target, subject, content)
    
    if success:
        print("✅ 邮件发送成功！")
        sys.exit(0)
    else:
        print(f"❌ 邮件发送失败: {message}")
        sys.exit(1)

if __name__ == "__main__":
    main()
