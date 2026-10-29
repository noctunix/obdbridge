export 'src/elm_response_parser.dart';
export 'src/guard.dart';
export 'src/module_setup.dart';
export 'src/obdbridge.dart';
// `NativeObdApi` itself is deliberately not exported — `ObdBridge` is the
// only supported way to reach it, so every command a plan carries is
// guaranteed to have passed a `CommandPolicy` first. Only the message/enum
// types `ObdBridge`'s own public API and `ObdEventsApi` need are re-exported.
export 'src/obdbridge_api.g.dart'
    show
        ObdAdapterMessage,
        ObdBackgroundRecordingCapabilityMessage,
        ObdBridgeConfigMessage,
        ObdCapabilitiesMessage,
        ObdConsecutiveFailuresPolicyMessage,
        ObdEventsApi,
        ObdLimitsMessage,
        ObdLinkFailureMessage,
        ObdLinkStateMessage,
        ObdLinkStateValueMessage,
        ObdLiveStateMessage,
        ObdModuleMessage,
        ObdOneShotEnqueueResultMessage,
        ObdPermissionStatusMessage,
        ObdPlanMessage,
        ObdRecordingNotificationMessage,
        ObdRecordingOptionsMessage,
        ObdRecordingStateMessage,
        ObdRecordingStateValueMessage,
        ObdSignalExchangeMessage,
        ObdStartRecordingRequest,
        ObdStepMessage,
        ObdTransportStatusMessage;

/// Current obdbridge package version.
///
/// Kept in sync with the `version:` field in `pubspec.yaml`.
const String obdbridgeVersion = '0.2.2';
