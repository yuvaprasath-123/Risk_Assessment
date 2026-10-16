package com.ofss.dto;
import jakarta.validation.constraints.NotNull;
public record SimulationModeRequest(@NotNull Boolean simulateFailure) {}
