package vn.edu.phenikaa.alumni.mail;

import vn.edu.phenikaa.alumni.api.EmailDeliveryException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SmtpEmailDeliveryAdapter implements EmailDeliveryPort {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String mailHost;
    private final String fromAddress;

    public SmtpEmailDeliveryAdapter(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${app.mail.from}") String fromAddress) {
        this.mailSenderProvider = mailSenderProvider;
        this.mailHost = mailHost;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendOtp(String recipient, String otp, String purpose) {
        sendMessage(recipient, "Mã xác minh tài khoản Phenikaa",
                "Mã xác minh cho yêu cầu " + purpose + " là " + otp
                        + ". Mã có hiệu lực trong 10 phút và chỉ sử dụng được một lần.");
    }

    @Override
    public void sendMessage(String recipient, String subject, String body) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (!StringUtils.hasText(mailHost) || mailSender == null) {
            throw new EmailDeliveryException();
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            throw new EmailDeliveryException();
        }
    }
}
