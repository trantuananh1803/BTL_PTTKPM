package vn.edu.phenikaa.alumni.mail;

public interface EmailDeliveryPort {

    void sendOtp(String recipient, String otp, String purpose);

    void sendMessage(String recipient, String subject, String body);
}
