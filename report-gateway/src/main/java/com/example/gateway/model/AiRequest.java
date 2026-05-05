package com.example.gateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiRequest(
        @NotBlank(message = "prompt is required")
        @Size(max = 500, message = "prompt must be under 500 characters")
        String prompt
) {}
