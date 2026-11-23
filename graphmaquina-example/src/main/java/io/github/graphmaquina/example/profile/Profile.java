package io.github.graphmaquina.example.profile;

import io.github.graphmaquina.annotation.GraphProperty;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A profile that a user fills in. The machine reads and writes its state, which starts at DRAFT; the application sets
 * the name and the address as they arrive, and an empty text stands for the one not given yet.
 */
@RequiredArgsConstructor
public final class Profile {

    @GraphProperty
    @Getter
    @NonNull
    private final String id;
    @GraphProperty
    @Getter
    private ProfileStatus status = ProfileStatus.DRAFT;
    @Getter
    private String name = "";
    @Getter
    private String address = "";

    public void setStatus(@NonNull ProfileStatus status) {
        this.status = status;
    }

    public void setName(@NonNull String name) {
        this.name = name;
    }

    public void setAddress(@NonNull String address) {
        this.address = address;
    }

    @Override
    public String toString() {
        return "Profile#" + id;
    }
}
