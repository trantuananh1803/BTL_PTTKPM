package vn.edu.phenikaa.alumni.api;

public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException() {
        super("Email delivery is unavailable");
    }
}
