from mail_sender import MailSender

def main():
    sender = MailSender()
    
    target = "example@qq.com"  # 请替换为真实目标邮箱
    subject = "来自 OldChat 的通知"
    content = "你好，这是一条通过 Python 程序自动发送的测试邮件。"
    
    print(f"正在发送邮件至 {target}...")
    success, message = sender.send_mail(target, subject, content)
    
    if success:
        print("✅ 邮件发送成功！")
    else:
        print(f"❌ 邮件发送失败: {message}")

if __name__ == "__main__":
    main()
