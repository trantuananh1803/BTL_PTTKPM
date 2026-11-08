package vn.edu.phenikaa.alumni.api;

import java.util.Map;

public record ApiErrorResponse(String code, String message, Map<String, String> fieldErrors) {

    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(code, message, Map.of());
    }
}
