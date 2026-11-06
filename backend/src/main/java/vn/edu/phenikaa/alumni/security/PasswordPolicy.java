package vn.edu.phenikaa.alumni.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.edu.phenikaa.alumni.api.ApiException;

import java.nio.charset.StandardCharsets;

@Component
public class PasswordPolicy {
    public void validate(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_POLICY_VIOLATION",
                    "Mật khẩu phải có ít nhất 12 ký tự và tối đa 72 byte UTF-8.");
        }
    }
}
