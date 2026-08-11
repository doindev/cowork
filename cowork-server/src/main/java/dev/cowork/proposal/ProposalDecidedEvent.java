package dev.cowork.proposal;

/** Published when a proposal reaches a terminal PASSED/REJECTED state. */
public record ProposalDecidedEvent(Proposal proposal) {
}
