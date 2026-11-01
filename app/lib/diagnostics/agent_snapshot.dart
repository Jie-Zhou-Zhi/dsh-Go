// `$DSH_HOME/dsh-app/diagnostics.md`: one redacted, model-facing snapshot of
// this phone, written so the agent can read it *before* it tries to help with a
// support question instead of asking the person to describe everything.
//
// It is not the export bundle (diagnostics_bundle.dart → ZIP for a bug report):
// this one is small, plain Markdown, lives inside the guest and is meant to be
// read by the agent itself. The bundled skill `dsh-go-support` tells it to.
//
// Every string goes through `redactDiagnostics`, exactly like the bundle.

import 'dart:io';

import '../state/app_info.dart';
import 'diagnostics_bundle.dart';
import 'redact.dart';

/// Where the file lives inside the guest (`<rootfs>/root/.dsh/…`).
const agentDiagnosticsRelPath = 'dsh-app/diagnostics.md';

/// Engine log lines carried in the file (the export bundle keeps 2000).
const agentDiagnosticsLogTail = 40;

/// Recent app errors carried in the file.
const agentDiagnosticsErrors = 5;

/// What happened to the file.
enum AgentSnapshotWrite {
  /// Written (first time or refreshed).
  written,

  /// Same content already on disk.
  unchanged,

  /// The guest has no DSH home yet: the engine never ran.
  noHome,

  /// IO failed; the caller keeps going.
  failed,
}

/// The Markdown the agent reads. Pure: every input comes from [s], so tests can
/// build one by hand.
String agentDiagnosticsMarkdown(DiagnosticsSnapshot s) {
  final status = s.status;
  final b = StringBuffer();
  void line([String text = '']) => b.writeln(text);
  void facts(String title, Map<String, Object?> map) {
    line('## $title');
    if (map.isEmpty) {
      line('- （无）');
    } else {
      for (final entry in map.entries) {
        final value = entry.value;
        if (value == null) continue;
        line('- ${entry.key}: ${redactDiagnostics('$value')}');
      }
    }
    line();
  }

  line('# DSH Go 本机诊断');
  line();
  line('由 DSH Go App 写入，不含 API Key、密码或对话内容。把这里当成只读事实，');
  line('不要照抄给用户，除非他问；需要更多细节时再用工具去看。');
  line();
  line('- 生成时间: ${_clock(s.at)}');
  line('- App 版本: ${redactDiagnostics(AppInfo.version)}');
  line();

  line('## 运行时');
  line('- 状态: ${status.phase.name}');
  line('- 引擎模式: ${status.mode.name}');
  line('- 安全模式: ${status.safeState.name}${status.safeReason == null ? '' : '（${status.safeReason}）'}');
  line('- 连续失败次数: ${status.strikes}');
  line('- ABI: ${status.abi ?? '—'}');
  line('- 自带运行时版本: ${status.bundledVersion ?? '—'}');
  line('- 已装运行时版本: ${status.installedVersion ?? '—'}');
  line('- DSH: ${status.dshVersion ?? '—'}');
  line('- Node.js: ${status.nodeVersion ?? '—'}');
  line('- 引擎地址: ${status.baseUrl ?? '—'}');
  if (status.error case final error?) line('- 最近错误: ${redactDiagnostics(error)}');
  line();

  facts('设备', s.native.device);
  facts('App 与运行时清单', {...s.native.app, ...s.native.manifest});
  facts('启动', status.launchDiagnostics);
  facts('连接', s.connection);
  facts('性能', {...s.performance, 'voiceMode': s.voiceMode.name});

  line('## 最近的 App 错误');
  if (s.appErrors.isEmpty) {
    line('- （无）');
  } else {
    for (final error in s.appErrors.reversed.take(agentDiagnosticsErrors)) {
      final first = error.message.split('\n').first;
      line('- ${_clock(error.at)} · ${error.source.name} · ${redactDiagnostics(first)}');
    }
  }
  line();

  line('## 引擎日志尾部（最后 $agentDiagnosticsLogTail 行）');
  line('```');
  final tail = s.engineLog.length <= agentDiagnosticsLogTail
      ? s.engineLog
      : s.engineLog.sublist(s.engineLog.length - agentDiagnosticsLogTail);
  if (tail.isEmpty) {
    line('（没有日志）');
  } else {
    for (final text in tail) {
      line(redactDiagnostics(text));
    }
  }
  line('```');

  if (s.failureLog.isNotEmpty) {
    line();
    line('## 上一次失败的日志');
    line('```');
    for (final text in s.failureLog.take(agentDiagnosticsLogTail)) {
      line(redactDiagnostics(text));
    }
    line('```');
  }
  return b.toString();
}

/// Writes [markdown] to `<rootfs>/root/.dsh/dsh-app/diagnostics.md`, replacing
/// what was there. Synchronous IO: call it off the UI thread.
AgentSnapshotWrite writeAgentDiagnostics(Directory rootfs, String markdown) {
  final home = Directory('${rootfs.path}/root/.dsh');
  if (!home.existsSync()) return AgentSnapshotWrite.noHome;
  final file = File('${home.path}/$agentDiagnosticsRelPath');
  try {
    if (file.existsSync() && file.readAsStringSync() == markdown) return AgentSnapshotWrite.unchanged;
    Directory('${home.path}/dsh-app').createSync(recursive: true);
    // Temp file + rename: the agent must never read a half-written snapshot.
    final temp = File('${file.path}.tmp');
    temp.writeAsStringSync(markdown, flush: true);
    temp.renameSync(file.path);
    return AgentSnapshotWrite.written;
  } catch (_) {
    return AgentSnapshotWrite.failed;
  }
}

String _clock(DateTime at) {
  String two(int n) => n.toString().padLeft(2, '0');
  final offset = at.timeZoneOffset;
  final sign = offset.isNegative ? '-' : '+';
  final hh = two(offset.inHours.abs());
  final mm = two(offset.inMinutes.abs() % 60);
  return '${at.year}-${two(at.month)}-${two(at.day)} ${two(at.hour)}:${two(at.minute)}:${two(at.second)} '
      '($sign$hh:$mm)';
}
