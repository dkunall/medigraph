package com.medigraph.service.identity.adapter.in.web;

import com.medigraph.mpi.ReviewTask;
import com.medigraph.service.identity.application.StewardReview;
import com.medigraph.service.identity.application.StewardReview.Resolution;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "mpi-review")
@PreAuthorize("hasAnyRole('STEWARD','ADMIN')")
@RestController
@RequestMapping("/api/v1/mpi/reviews")
public class MpiReviewController {

    public record ResolveRequest(boolean merge) {
    }

    private final StewardReview review;

    public MpiReviewController(StewardReview review) {
        this.review = review;
    }

    @GetMapping
    public List<ReviewTask> pending() {
        return review.pending();
    }

    @PostMapping("/{taskId}/resolution")
    public Resolution resolve(@PathVariable String taskId, @RequestBody ResolveRequest r) {
        return review.resolve(taskId, r.merge());
    }
}
