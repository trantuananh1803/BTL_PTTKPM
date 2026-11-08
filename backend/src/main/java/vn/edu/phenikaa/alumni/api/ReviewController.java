package vn.edu.phenikaa.alumni.api;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.phenikaa.alumni.review.ReviewService;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/v1/staff/reviews")
@PreAuthorize("hasAnyRole('FACULTY_STAFF','ADMIN')")
public class ReviewController {
    private final ReviewService reviewService;
    public ReviewController(ReviewService reviewService) { this.reviewService = reviewService; }

    @GetMapping
    List<AdminDtos.ReviewItem> queue(@RequestParam String type,
                                     @AuthenticationPrincipal AccountPrincipal principal) {
        return reviewService.queue(type, principal);
    }

    @PostMapping("/{type}/{id}/decision")
    AuthDtos.MessageResponse decide(@PathVariable String type, @PathVariable long id,
                                    @Valid @RequestBody AdminDtos.ReviewDecisionRequest request,
                                    @AuthenticationPrincipal AccountPrincipal principal) {
        reviewService.decide(type, id, request, principal);
        return new AuthDtos.MessageResponse("Đã ghi nhận quyết định kiểm duyệt.");
    }
}
