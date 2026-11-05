package com.onlinehomework.grading.api;
import com.onlinehomework.grading.domain.GradeStatus; import jakarta.validation.constraints.*; import java.math.BigDecimal; import java.time.Instant; import java.util.UUID;
public final class GradeDtos { private GradeDtos(){} public record CreateGradeRequest(@DecimalMin("0") BigDecimal score,@NotNull @Size(max=10000) String feedback){} public record GradeResponse(UUID id,UUID submissionId,int revision,GradeStatus status,BigDecimal score,String feedback,Instant createdAt,Instant publishedAt){} }
