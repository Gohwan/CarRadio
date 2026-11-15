package com.example.carradio

import org.junit.Assert.*
import org.junit.Test

class DrivingUiStateTest {
    private fun restricted() = DrivingUiState.unrestricted().copy(requiresOptimization = true)

    @Test fun unavailableStateCannotUnlockEditingSpeechOrPresetTransmission() {
        val state = DrivingUiState()
        assertTrue(state.compact)
        assertFalse(state.canConfigure)
        assertFalse(state.canTalk)
        SignalPreset.entries.forEach { assertFalse(state.canSendPreset(it)) }
        assertNull(state.speakerLabel("1234"))
    }

    @Test fun platformBaselineAllowsConfigurationWithoutClaimingGearOrSpeed() {
        val state = DrivingUiState.unrestricted()
        assertTrue(state.canConfigure)
        assertTrue(state.canTalk)
        assertFalse(state.compact)
    }

    @Test fun optimizationRequirementUsesCompactUiEvenWhenThereAreNoIndividualFlags() {
        val state = restricted()
        assertTrue(state.compact)
        assertFalse(state.canConfigure)
        assertTrue(state.canTalk)
        SignalPreset.entries.forEach { assertTrue(state.canSendPreset(it)) }
    }

    @Test fun contradictoryNonzeroRestrictionsNeverUnlockTheFullUi() {
        val state = DrivingUiState.unrestricted().copy(activeRestrictions = 64)
        assertFalse(state.canConfigure)
        assertTrue(state.compact)
    }

    @Test fun textRestrictionBlocksAllPresetsAndSenderTextWhileVoiceRemainsAvailable() {
        val state = restricted().copy(noTextMessages = true)
        SignalPreset.entries.forEach { assertFalse(state.canSendPreset(it)) }
        assertNull(state.speakerLabel("입력번호 1234"))
        assertTrue(state.canTalk)
    }

    @Test fun stringLimitsNeverSilentlyTruncateAnOutgoingSignal() {
        val preset = SignalPreset.HELP
        assertTrue(restricted().copy(stringLimit = preset.text.length).canSendPreset(preset))
        assertFalse(restricted().copy(stringLimit = preset.text.length - 1).canSendPreset(preset))
        assertFalse(restricted().copy(stringLimit = 0).canSendPreset(preset))
        assertEquals("1234", restricted().copy(stringLimit = 4).speakerLabel("12345678"))
        assertNull(restricted().copy(stringLimit = 0).speakerLabel("1234"))
    }

    @Test fun zeroOrInvalidContentBoundsCannotOpenAnExtraConfirmationScreen() {
        listOf(0, -1).forEach { limit ->
            assertFalse(restricted().copy(contentLimit = limit).canSendPreset(SignalPreset.DANGER))
            assertFalse(restricted().copy(depthLimit = limit).canSendPreset(SignalPreset.HELP))
        }
        assertTrue(restricted().copy(contentLimit = 1, depthLimit = 1).canSendPreset(SignalPreset.DANGER))
    }

    @Test fun noDebugModeCanRelaxAnExistingPlatformRestriction() {
        val bases = listOf(DrivingUiState(), restricted().copy(noTextMessages = true),
            restricted().copy(stringLimit = 1), restricted().copy(unsupportedRestrictions = true))
        for (base in bases) for (mode in DrivingTestMode.entries) {
            val simulated = base.withTestMode(mode)
            assertFalse(simulated.canConfigure)
            if (!base.canTalk) assertFalse(simulated.canTalk)
            for (preset in SignalPreset.entries) if (!base.canSendPreset(preset)) assertFalse(simulated.canSendPreset(preset))
        }
    }

    @Test fun debugRestrictedAndUnknownViewsHaveDifferentTransmitPermissions() {
        val base = DrivingUiState.unrestricted()
        assertTrue(base.withTestMode(DrivingTestMode.RESTRICTED).canTalk)
        assertFalse(base.withTestMode(DrivingTestMode.UNAVAILABLE).canTalk)
        assertFalse(base.withTestMode(DrivingTestMode.TEXT_BLOCKED).canSendPreset(SignalPreset.HELP))
        assertTrue(base.withTestMode(DrivingTestMode.RESTRICTED).simulated)
        assertSame(base, base.withTestMode(DrivingTestMode.AUTOMATIC))
    }

    @Test fun unsupportedNewPlatformFlagsDoNotFallBackToUnrestrictedUi() {
        val state = DrivingUiState.unrestricted().copy(unsupportedRestrictions = true)
        assertFalse(state.canConfigure)
        assertFalse(state.canTalk)
        assertFalse(state.canSendPreset(SignalPreset.DANGER))
        assertNull(state.speakerLabel("1234"))
    }

    @Test fun fixedSignalsFitTheExistingTextTransportLimit() {
        SignalPreset.entries.forEach {
            assertTrue(it.text.isNotBlank())
            assertTrue(it.text.length <= 200)
        }
    }
}
