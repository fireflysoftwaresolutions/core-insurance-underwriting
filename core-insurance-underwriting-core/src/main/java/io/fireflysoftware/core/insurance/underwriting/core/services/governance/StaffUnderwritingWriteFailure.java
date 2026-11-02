package io.fireflysoftware.core.insurance.underwriting.core.services.governance;

import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.GovernedUnderwritingCommandResponse.Status;

public final class StaffUnderwritingWriteFailure extends RuntimeException {
    private final Status status;
    public StaffUnderwritingWriteFailure(Status status){super("Protected owner underwriting write outcome unavailable");this.status=java.util.Objects.requireNonNull(status);}
    public Status status(){return status;}
}
