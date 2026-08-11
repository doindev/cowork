package dev.cowork.config;

import java.util.UUID;

/** Entities whose UUID primary key is assigned in the application before insert. */
public interface UuidAssignable {

    UUID getId();

    void setId(UUID id);
}
