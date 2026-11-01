import 'dart:io';

import 'package:dsh_app/diagnostics/agent_snapshot.dart';
import 'package:dsh_app/diagnostics/diagnostics_bundle.dart';
import 'package:dsh_app/diagnostics/diagnostics_channel.dart';
import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/state/settings_state.dart';
import 'package:flutter_test/flutter_test.dart';

// The file the agent reads first ($DSH_HOME/dsh-app/diagnostics.md): what it
// says, and that writing it is idempotent and never touches a guest without a
// DSH home.
void main() {
  DiagnosticsSnapshot snapshot({List<String> log = const ['boot ok']}) => DiagnosticsSnapshot(
    at: DateTime.utc(2026, 11, 1, 4, 30, 12),
    status: const RuntimeStatus(
      phase: RuntimePhase.running,
      abi: 'arm64-v8a',
      bundledVersion: '1.0.4',
      installedVersion: '1.0.4',
      dshVersion: '0.9.3',
      nodeVersion: 'v22.11.0',
      baseUrl: 'http://127.0.0.1:5041',
    ),
    settings: const AppSettings(),
    native: const NativeDiagnostics(
      device: {'model': 'Pixel 8', 'android': '15', 'sdkInt': 35},
      manifest: {'files': 1234},
    ),
    connection: const {'kind': 'local'},
    performance: const {'tier': 'high'},
    engineLog: log,
  );

  group('agentDiagnosticsMarkdown', () {
    test('carries the facts the agent needs before it asks anything', () {
      final md = agentDiagnosticsMarkdown(snapshot());
      expect(md, startsWith('# DSH Go 本机诊断'));
      expect(md, contains('生成时间: 2026-11-01 04:30:12'));
      expect(md, contains('状态: running'));
      expect(md, contains('ABI: arm64-v8a'));
      expect(md, contains('DSH: 0.9.3'));
      expect(md, contains('Node.js: v22.11.0'));
      expect(md, contains('model: Pixel 8'));
      expect(md, contains('sdkInt: 35'));
      expect(md, contains('kind: local'));
      expect(md, contains('tier: high'));
      expect(md, contains('## 引擎日志尾部'));
      expect(md, contains('boot ok'));
      // Nothing may render as a Dart null: the agent would read it as a value.
      expect(md, isNot(contains('null')));
    });

    test('keeps only the tail of a long engine log', () {
      final log = [for (var i = 0; i < 200; i++) 'line $i'];
      final md = agentDiagnosticsMarkdown(snapshot(log: log));
      expect(md, contains('line 199'));
      expect(md, contains('line ${200 - agentDiagnosticsLogTail}'));
      expect(md, isNot(contains('line 0\n')));
    });

    test('says so when there is nothing to show', () {
      final md = agentDiagnosticsMarkdown(
        DiagnosticsSnapshot(
          at: DateTime.utc(2026, 11, 1),
          status: const RuntimeStatus.unknown(),
          settings: const AppSettings(),
        ),
      );
      expect(md, contains('引擎日志尾部'));
      expect(md, contains('（没有日志）'));
      expect(md, contains('（无）'));
    });
  });

  group('writeAgentDiagnostics', () {
    late Directory root;

    setUp(() => root = Directory.systemTemp.createTempSync('dsh-agent-snapshot'));
    tearDown(() => root.deleteSync(recursive: true));

    test('no DSH home means no file and no failure', () {
      expect(writeAgentDiagnostics(root, '# hi'), AgentSnapshotWrite.noHome);
      expect(File('${root.path}/root/.dsh/dsh-app/diagnostics.md').existsSync(), isFalse);
    });

    test('writes, then reports unchanged for the same content', () {
      Directory('${root.path}/root/.dsh').createSync(recursive: true);
      expect(writeAgentDiagnostics(root, '# first'), AgentSnapshotWrite.written);
      final file = File('${root.path}/root/.dsh/dsh-app/diagnostics.md');
      expect(file.readAsStringSync(), '# first');
      expect(writeAgentDiagnostics(root, '# first'), AgentSnapshotWrite.unchanged);
      expect(writeAgentDiagnostics(root, '# second'), AgentSnapshotWrite.written);
      expect(file.readAsStringSync(), '# second');
      // No temp file is left behind for the agent to trip over.
      expect(
        Directory('${root.path}/root/.dsh/dsh-app').listSync().map((e) => e.path.split('/').last),
        ['diagnostics.md'],
      );
    });
  });
}
