package io.fireflysoftware.core.insurance.underwriting.models.config.impl;

import io.fireflysoftware.core.insurance.underwriting.interfaces.enums.ReferralStatus;
import org.springframework.data.r2dbc.convert.EnumWriteSupport;

public final class ReferralStatusWriteConverterImpl extends EnumWriteSupport<ReferralStatus> {}
