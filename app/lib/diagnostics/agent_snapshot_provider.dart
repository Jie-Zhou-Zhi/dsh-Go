// When `$DSH_HOME/dsh-app/diagnostics.md` gets written: once each time the
// engine reports running (the agent then has something to read from its first
// turn), and on demand from 设置 → 诊断.

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../runtime/runtime_bridge.dart';
import '../runtime/runtime_state.dart';
import '../share_code/guest_fs.dart';
import 'agent_snapshot.dart';
import 'diagnostics_bundle.dart';

/// Writes the snapshot now and reports what happened. Never throws: diagnostics
/// must not break a start-up path.
final agentDiagnosticsWriterProvider = Provider<Future<AgentSnapshotWrite> Function()>((ref) {
  return () async {
    try {
      final rootfs = (await GuestFs.local())?.root;
      if (rootfs == null) return AgentSnapshotWrite.noHome;
      final snapshot = await collectDiagnostics(ref, DateTime.now());
      final markdown = agentDiagnosticsMarkdown(snapshot);
      // Synchronous IO and log reading: keep it off the UI thread.
      return await Future<AgentSnapshotWrite>(() => writeAgentDiagnostics(rootfs, markdown));
    } catch (_) {
      return AgentSnapshotWrite.failed;
    }
  };
});

/// Watched by the shell. Writes once per engine start; the file is small and
/// `unchanged` when nothing moved.
final agentDiagnosticsAutoWriteProvider = Provider<void>((ref) {
  void write() => unawaited(ref.read(agentDiagnosticsWriterProvider)());
  ref.listen(localRuntimeProvider.select((s) => s.status.phase), (previous, next) {
    if (next == RuntimePhase.running && previous != RuntimePhase.running) write();
  });
  // Already running when this provider first came alive (warm restart).
  if (ref.read(localRuntimeProvider).status.phase == RuntimePhase.running) write();
});
