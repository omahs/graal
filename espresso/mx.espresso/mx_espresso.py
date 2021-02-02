#
# Copyright (c) 2013, 2020, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#

import os

import mx
import mx_espresso_benchmarks  # pylint: disable=unused-import
import mx_sdk_vm
from mx_gate import Task, add_gate_runner
from mx_jackpot import jackpot


_suite = mx.suite('espresso')


def _espresso_launcher_command(args):
    """Espresso launcher embedded in GraalVM + arguments"""
    import mx_sdk_vm_impl
    bin_dir = os.path.join(mx_sdk_vm_impl.graalvm_home(fatalIfMissing=True), 'bin')
    exe = os.path.join(bin_dir, mx.exe_suffix('espresso'))
    if not os.path.exists(exe):
        exe = os.path.join(bin_dir, mx.cmd_suffix('espresso'))
    return [exe] + args

def _espresso_java_command(args):
    """Java launcher using libespresso in GraalVM + arguments"""
    import mx_sdk_vm_impl
    bin_dir = os.path.join(mx_sdk_vm_impl.graalvm_home(fatalIfMissing=True), 'bin')
    exe = os.path.join(bin_dir, mx.exe_suffix('java'))
    if not os.path.exists(exe):
        exe = os.path.join(bin_dir, mx.cmd_suffix('java'))
    return [exe, '-truffle'] + args

def _espresso_standalone_command(args):
    """Espresso standalone command from distribution jars + arguments"""
    vm_args, args = mx.extract_VM_args(args, useDoubleDash=True, defaultAllVMArgs=False)
    return (
        vm_args
        + mx.get_runtime_jvm_args(['ESPRESSO', 'ESPRESSO_LAUNCHER'], jdk=mx.get_jdk())
        + [mx.distribution('ESPRESSO_LAUNCHER').mainClass] + args
    )


def _run_espresso_launcher(args=None, cwd=None, nonZeroIsFatal=True):
    """Run Espresso launcher within a GraalVM"""
    return mx.run(_espresso_launcher_command(args), cwd=cwd, nonZeroIsFatal=nonZeroIsFatal)


def _run_espresso_standalone(args=None, cwd=None, nonZeroIsFatal=True):
    """Run standalone Espresso (not as part of GraalVM) from distribution jars"""
    return mx.run_java(_espresso_standalone_command(args), cwd=cwd, nonZeroIsFatal=nonZeroIsFatal)


def _run_espresso_java(args=None, cwd=None, nonZeroIsFatal=True):
    """Run espresso through the standard java launcher within a GraalVM"""
    return mx.run(_espresso_java_command(args), cwd=cwd, nonZeroIsFatal=nonZeroIsFatal)


def _run_espresso_meta(args, nonZeroIsFatal=True):
    """Run Espresso (standalone) on Espresso (launcher)"""
    return _run_espresso_launcher(['--vm.Xss4m'] + _espresso_standalone_command(args), nonZeroIsFatal=nonZeroIsFatal)


class EspressoTags:
    jackpot = 'jackpot'
    verify = 'verify'


def _espresso_gate_runner(args, tasks):
    # Jackpot configuration is inherited from Truffle.
    with Task('Jackpot', tasks, tags=[EspressoTags.jackpot]) as t:
        if t:
            jackpot(['--fail-on-warnings'], suite=None, nonZeroIsFatal=True)

    mokapot_header_gate_name = 'Verify consistency of mokapot headers'
    with Task(mokapot_header_gate_name, tasks, tags=[EspressoTags.verify]) as t:
        if t:
            import mx_sdk_vm_impl
            run_instructions = "$ mx --dynamicimports=/substratevm --native-images=lib:espresso gate --all-suites --task '{}'".format(mokapot_header_gate_name)
            if mx_sdk_vm_impl._skip_libraries(espresso_library_config):
                mx.abort("""\
The registration of the Espresso library ('lib:espresso') is skipped. Please run this gate as follows:
{}""".format(run_instructions))

            errors = False
            mokapot_dir = os.path.join(mx.project('com.oracle.truffle.espresso.mokapot').dir, 'include')
            libespresso_dir = mx.project(mx_sdk_vm_impl.GraalVmNativeImage.project_name(espresso_library_config)).get_output_root()

            for header in ['libespresso_dynamic.h', 'graal_isolate_dynamic.h']:
                committed_header = os.path.join(mokapot_dir, header)
                if not mx.exists(committed_header):
                    mx.abort("Cannot locate '{}'. Was the file moved or renamed?".format(committed_header))

                generated_header = os.path.join(libespresso_dir, header)
                if not mx.exists(generated_header):
                    mx.abort("Cannot locate '{}'. Did you forget to build? Example:\n'mx --dynamicimports=/substratevm --native-images=lib:espresso build'".format(generated_header))

                committed_header_copyright = []
                with open(committed_header, 'r') as committed_header_file:
                    for line in committed_header_file.readlines():
                        if line == '/*\n' or line.startswith(' *') or line == '*/\n':
                            committed_header_copyright.append(line)
                        else:
                            break

                with open(generated_header, 'r') as generated_header_file:
                    generated_header_lines = generated_header_file.readlines()

                errors = errors or mx.update_file(committed_header, ''.join(committed_header_copyright + generated_header_lines), showDiff=True)

            if errors:
                mx.abort("""\
One or more header files in the include dir of the mokapot project ('{committed}/') do not match those generated by Native Image ('{generated}/').
To fix the issue, run this gate locally:
{instructions}
And adapt the code to the modified headers in '{committed}'.
""".format(committed=os.path.relpath(mokapot_dir, _suite.vc_dir), generated=os.path.relpath(libespresso_dir, _suite.vc_dir), instructions=run_instructions))


# REGISTER MX GATE RUNNER
#########################
add_gate_runner(_suite, _espresso_gate_runner)


if mx_sdk_vm.base_jdk_version() > 8:
    if mx.is_windows():
        lib_espresso_cp = '%GRAALVM_HOME%\\lib\\graalvm\\lib-espresso.jar'
    else:
        lib_espresso_cp = '${GRAALVM_HOME}/lib/graalvm/lib-espresso.jar'
else:
    if mx.is_windows():
        lib_espresso_cp = '%GRAALVM_HOME%\\jre\\lib\\graalvm\\lib-espresso.jar'
    else:
        lib_espresso_cp = '${GRAALVM_HOME}/jre/lib/graalvm/lib-espresso.jar'


espresso_library_config = mx_sdk_vm.LibraryConfig(
    destination='lib/<lib:espresso>',
    jar_distributions=['espresso:LIB_ESPRESSO'],
    build_args=[
        '--language:java',
        '--tool:all',
    ],
    home_finder=True,
)

mx_sdk_vm.register_graalvm_component(mx_sdk_vm.GraalVmLanguage(
    suite=_suite,
    name='Java on Truffle',
    short_name='java',
    installable_id='espresso',
    installable=True,
    license_files=[],
    third_party_license_files=[],
    dependencies=['Truffle', 'Truffle NFI', 'ejvm'],
    truffle_jars=['espresso:ESPRESSO'],
    support_distributions=['espresso:ESPRESSO_SUPPORT'],
    library_configs=[espresso_library_config],
    polyglot_lib_jar_dependencies=['espresso:LIB_ESPRESSO'],
    has_polyglot_lib_entrypoints=True,
    priority=1,
    post_install_msg="""
This version of Java on Truffle is experimental. We do not recommended it for production use.

Usage: java -truffle [-options] class [args...]
           (to execute a class)
    or java -truffle [-options] -jar jarfile [args...]
           (to execute a jar file)

To rebuild the polyglot library:
    gu rebuild-images libpolyglot -cp """ + lib_espresso_cp,
))

mx_sdk_vm.register_graalvm_component(mx_sdk_vm.GraalVmJreComponent(
    suite=_suite,
    name='Espresso libjvm',
    short_name='ejvm',
    dir_name='truffle',
    installable_id='espresso',
    installable=True,
    license_files=[],
    third_party_license_files=[],
    dependencies=['Java on Truffle'],
    support_libraries_distributions=['espresso:ESPRESSO_JVM_SUPPORT'],
    priority=2,
))

mx_sdk_vm.register_graalvm_component(mx_sdk_vm.GraalVmLanguage(
    suite=_suite,
    name='Espresso Launcher',
    short_name='elau',
    installable=False,
    license_files=[],
    third_party_license_files=[],
    dependencies=['Java on Truffle'],
    truffle_jars=[],
    launcher_configs=[
        mx_sdk_vm.LanguageLauncherConfig(
            destination='bin/<exe:espresso>',
            jar_distributions=['espresso:ESPRESSO_LAUNCHER'],
            main_class='com.oracle.truffle.espresso.launcher.EspressoLauncher',
            build_args=[],
            language='java',
        )
    ],
))


# Register new commands which can be used from the commandline with mx
mx.update_commands(_suite, {
    'espresso': [_run_espresso_launcher, '[args]'],
    'espresso-standalone': [_run_espresso_standalone, '[args]'],
    'espresso-java': [_run_espresso_java, '[args]'],
    'espresso-meta': [_run_espresso_meta, '[args]'],
})

# Build configs
# pylint: disable=bad-whitespace
mx_sdk_vm.register_vm_config('espresso-jvm',       ['java', 'ejvm', 'elau', 'nfi', 'sdk', 'tfl'                                        ], _suite, env_file='jvm')
mx_sdk_vm.register_vm_config('espresso-jvm-ce',    ['java', 'ejvm', 'elau', 'nfi', 'sdk', 'tfl', 'cmp'                                 ], _suite, env_file='jvm-ce')
mx_sdk_vm.register_vm_config('espresso-jvm-ee',    ['java', 'ejvm', 'elau', 'nfi', 'sdk', 'tfl', 'cmp', 'cmpee'                        ], _suite, env_file='jvm-ee')
mx_sdk_vm.register_vm_config('espresso-native-ce', ['java', 'ejvm', 'elau', 'nfi', 'sdk', 'tfl', 'cmp'         , 'svm'         , 'tflm'], _suite, env_file='native-ce')
mx_sdk_vm.register_vm_config('espresso-native-ee', ['java', 'ejvm', 'elau', 'nfi', 'sdk', 'tfl', 'cmp', 'cmpee', 'svm', 'svmee', 'tflm'], _suite, env_file='native-ee')
