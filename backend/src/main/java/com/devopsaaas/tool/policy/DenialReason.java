package com.devopsaaas.tool.policy;

/** Why a proposal was denied, in the order the validation chain checks them (docs/03-arquitetura.md, 5.2). */
public enum DenialReason {
    ENVIRONMENT_UNAVAILABLE,
    UNKNOWN_TOOL,
    NOT_ALLOWED_BY_AUTONOMY,
    INVALID_ARGUMENTS,
    RESOURCE_NOT_ALLOWED,
    INSUFFICIENT_PERMISSION,
    BUDGET_EXCEEDED,
    /** The evaluation itself failed; the policy fails closed. */
    POLICY_ERROR
}
