import 'dart:convert';

import 'package:bett_box/common/common.dart';
import 'package:bett_box/enum/enum.dart';
import 'package:bett_box/plugins/vpn.dart';
import 'package:bett_box/providers/app.dart';
import 'package:bett_box/providers/state.dart';
import 'package:bett_box/state.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class VpnManager extends ConsumerStatefulWidget {
  final Widget child;

  const VpnManager({super.key, required this.child});

  @override
  ConsumerState<VpnManager> createState() => _VpnContainerState();
}

class _VpnContainerState extends ConsumerState<VpnManager> {
  @override
  void initState() {
    _initVpnHandler();
    super.initState();
    ref.listenManual(vpnStateProvider, (prev, next) {
      // Skip tip
      if (prev == null || prev == next) return;
      
      final prevProps = prev.vpnProps;
      final nextProps = next.vpnProps;
      
      // Check
      final onlySmartAutoStopChanged = prevProps.copyWith(
        smartAutoStop: nextProps.smartAutoStop,
        smartAutoStopNetworks: nextProps.smartAutoStopNetworks,
      ) == nextProps;
      
      final onlyQuickResponseChanged = prevProps.copyWith(
        quickResponse: nextProps.quickResponse,
      ) == nextProps;
      
      if (onlySmartAutoStopChanged || onlyQuickResponseChanged) {
        return; // No tip needed
      }
      
      showTip();
    });
  }

  void showTip() {
    debouncer.call(FunctionTag.vpnTip, () {
      if (ref.read(runTimeProvider.notifier).isStart) {
        globalState.showNotifier(
          appLocalizations.vpnTip,
          onAction: () async {
            await globalState.appController.updateStatus(false);
            await Future.delayed(const Duration(milliseconds: 500));
            await globalState.appController.updateStatus(true);
          },
          actionLabel: appLocalizations.restart,
        );
      }
    });
  }

  void _initVpnHandler() {
    if (!system.isAndroid) return;

    vpn?.handleGetStartForegroundParams = () async {
      final isSmartStopped = await vpn?.isSmartStopped() ?? false;

      if (isSmartStopped) {
        return json.encode({
          'title': appLocalizations.coreSuspended,
          'content': appLocalizations.smartAutoStopServiceRunning,
        });
      }

      return json.encode({
        'title': appLocalizations.coreConnected,
        'content': appLocalizations.serviceRunning,
      });
    };
  }

  @override
  Widget build(BuildContext context) {
    return widget.child;
  }
}
