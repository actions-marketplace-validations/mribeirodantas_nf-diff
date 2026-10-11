/*
 * Copyright 2026, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.seqera.nf.diff

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Integration coverage for {@link GitProvenance}. These tests shell out to a
 * real {@code git}, so they only run where git is on the PATH.
 */
@Requires({ isGitAvailable() })
class GitProvenanceTest extends Specification {

    @TempDir
    Path dir

    static boolean isGitAvailable() {
        try {
            return new ProcessBuilder(['git', '--version']).start().waitFor() == 0
        }
        catch( Exception ignored ) {
            return false
        }
    }

    private void git(String... args) {
        final cmd = (['git'] + (args as List)) as List<String>
        final proc = new ProcessBuilder(cmd).directory(dir.toFile()).start()
        proc.inputStream.text
        proc.errorStream.text
        assert proc.waitFor() == 0
    }

    private void initRepo() {
        git('init', '-q')
        git('config', 'user.email', 'test@example.com')
        git('config', 'user.name', 'Test')
        Files.write(dir.resolve('nextflow.config'), 'process.cpus = 1\n'.getBytes('UTF-8'))
        git('add', '.')
        git('commit', '-q', '-m', 'initial')
    }

    def 'reports the HEAD commit of a clean repository'() {
        given:
        initRepo()

        when:
        def state = new GitProvenance().inspect(dir)

        then:
        state.isRepo()
        state.headCommit ==~ /[0-9a-f]{40}/
        !state.dirty
    }

    def 'detects a dirty working tree from tracked-file changes'() {
        given:
        initRepo()

        when: 'a tracked file is modified without committing'
        Files.write(dir.resolve('nextflow.config'), 'process.cpus = 4\n'.getBytes('UTF-8'))
        def state = new GitProvenance().inspect(dir)

        then:
        state.isRepo()
        state.dirty
    }

    def 'reports a non-repository directory as unknown'() {
        when: 'the directory is not a git work tree'
        def state = new GitProvenance().inspect(dir)

        then:
        !state.isRepo()
        state.headCommit == null
        !state.dirty
    }

    private String head() {
        final proc = new ProcessBuilder(['git', 'rev-parse', 'HEAD']).directory(dir.toFile()).start()
        final out = proc.inputStream.text.trim()
        proc.errorStream.text
        assert proc.waitFor() == 0
        return out
    }

    def 'isCommit recognises commits of the repository, full or abbreviated (#9)'() {
        given:
        initRepo()
        def sha = head()
        def gp = new GitProvenance()

        expect:
        gp.isCommit(dir, sha)
        gp.isCommit(dir, sha.substring(0, 10))
        gp.isCommit(dir, sha.toUpperCase())
    }

    def 'isCommit rejects values that are not a commit of this repository (#9): #desc'() {
        given:
        initRepo()

        expect:
        !new GitProvenance().isCommit(dir, rev)

        where:
        desc                         | rev
        'a Nextflow scriptId'        | '47e64accfc5d8f61b1a3d9b3e0f1c2a4'
        'a foreign 40-hex commit'    | '8a7f5c0d3b2e1f4a6c9d8e7b5a4c3d2e1f0a9b8c'
        'a branch name'              | 'master'
        'a symbolic ref'             | 'HEAD'
        'too short to be an id'      | 'abc12'
        'blank'                      | '  '
        'null'                       | null
    }

    def 'isCommit is false outside a repository'() {
        expect:
        !new GitProvenance().isCommit(dir, '47e64accfc5d8f61b1a3d9b3e0f1c2a4')
        !new GitProvenance().isCommit(null, '47e64accfc')
    }

    def 'a null directory is handled gracefully'() {
        expect:
        !new GitProvenance().inspect(null).isRepo()
    }
}
