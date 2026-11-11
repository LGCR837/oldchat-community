import smtplib
from email.mime.text import MIMEText
from email.header import Header

class AliyunMailSender:
    def __init__(self, sender_email, smtp_password):
        """
        :param sender_email: 你在阿里云后台创建的发信地址
        :param smtp_password: 你在阿里云后台为该地址设置的 SMTP 密码
        """
        # 阿里云邮件推送 SMTP 服务器地址
        # 杭州：smtpdm.aliyun.com
        # 新加坡：smtpdm-ap-southeast-1.aliyuncs.com
        self.smtp_server = "smtpdm.aliyun.com"
        self.smtp_port = 465  # SSL 端口
        self.sender_email = sender_email
        self.smtp_password = smtp_password

    def send_mail(self, to_email, subject, content):
        """
        发送邮件
        :param to_email: 目标邮箱
        :param subject: 主题
        :param content: 内容
        :return: (bool, str)
        """
        try:
            message = MIMEText(content, 'plain', 'utf-8')
            message['From'] = self.sender_email
            message['To'] = to_email
            message['Subject'] = Header(subject, 'utf-8')

            # 阿里云 SMTP 强制要求发信人必须与登录账号一致
            server = smtplib.SMTP_SSL(self.smtp_server, self.smtp_port)
            server.login(self.sender_email, self.smtp_password)
            server.sendmail(self.sender_email, [to_email], message.as_string())
            server.quit()
            
            return True, "Success"
        except Exception as e:
            return False, str(e)

# 使用示例
if __name__ == "__main__":
    # 配置你的阿里云发信信息
    # sender = AliyunMailSender("notice@mail.xxx.com", "YourSmtpPassword")
    # sender.send_mail("test@qq.com", "阿里云测试", "这是通过阿里云发送的内容")
    print("AliyunMailSender 库已准备好。")
