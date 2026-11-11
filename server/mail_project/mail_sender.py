import os

import smtplib
from email.mime.text import MIMEText
from email.header import Header

class MailSender:
    def __init__(self):
        # 邮件服务器配置：通过环境变量注入，避免在源码中硬编码凭据
        self.smtp_server = os.environ.get("SMTP_HOST", "smtp.163.com")
        self.smtp_port = int(os.environ.get("SMTP_PORT", "465"))  # SSL 端口
        self.sender_email = os.environ.get("SMTP_USER", "")
        self.auth_code = os.environ.get("SMTP_AUTH_CODE", "")

    def send_mail(self, to_email, subject, content):
        """
        发送指定内容的邮件给指定目标
        :param to_email: 目标邮箱地址
        :param subject: 邮件主题
        :param content: 邮件正文内容
        :return: (bool, str) 是否成功及错误信息
        """
        try:
            # 创建邮件对象
            message = MIMEText(content, 'plain', 'utf-8')
            message['From'] = self.sender_email
            message['To'] = to_email
            message['Subject'] = Header(subject, 'utf-8')

            # 连接服务器并发送
            # 使用 SSL 连接
            server = smtplib.SMTP_SSL(self.smtp_server, self.smtp_port)
            server.login(self.sender_email, self.auth_code)
            server.sendmail(self.sender_email, [to_email], message.as_string())
            server.quit()
            
            return True, "Success"
        except Exception as e:
            return False, str(e)

# 测试代码
if __name__ == "__main__":
    sender = MailSender()
    # 示例用法
    # success, msg = sender.send_mail("target@example.com", "测试主题", "这是测试内容")
    # print(f"发送状态: {success}, 信息: {msg}")
    print("MailSender 库已就绪。")
