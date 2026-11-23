package com.example.zplanner

import com.example.zplanner.data.ProfilePatch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSerializationTest {
    @Test fun updatingNameDoesNotClearAvatar() {
        assertEquals("{\"displayName\":\"Minh An\"}", Json.encodeToString(ProfilePatch(displayName = "Minh An")))
    }

    @Test fun updatingAvatarDoesNotClearName() {
        assertEquals("{\"avatarBase64\":\"/9j/2Q==\"}", Json.encodeToString(ProfilePatch(avatarBase64 = "/9j/2Q==")))
    }
}
