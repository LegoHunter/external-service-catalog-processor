package io.legohunter.ingress.source.bricklink.orders;

public record BricklinkOrderProjectionResult(Long transactionId, boolean invoiced, int projectedItems, int projectedCosts) { }
