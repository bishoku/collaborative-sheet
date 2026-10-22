package com.bishokudev.sheets.ao;

import net.java.ao.Entity;
import net.java.ao.schema.*;

/** Immutable committed revision and durable idempotency receipt in the same row. */
public interface Revision extends Entity {
    @NotNull @Indexed String getSheetKey();
    void setSheetKey(String value);
    @NotNull @Unique String getRevisionKey();
    void setRevisionKey(String value);
    @NotNull @Unique String getRequestKey();
    void setRequestKey(String value);
    @NotNull String getRequestHash();
    void setRequestHash(String value);
    @NotNull @Indexed int getSequence();
    void setSequence(int value);
    @NotNull @StringLength(StringLength.UNLIMITED) String getDocument();
    void setDocument(String value);
    @StringLength(StringLength.UNLIMITED) String getAction();
    void setAction(String value);
    @NotNull @Indexed String getActor();
    void setActor(String value);
    @NotNull long getCreatedAt();
    void setCreatedAt(long value);
}
