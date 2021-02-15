package io.codearte.gradle.nexus.functional.e2e

import groovy.transform.NotYetImplemented
import io.codearte.gradle.nexus.functional.BaseNexusStagingFunctionalSpec
import io.codearte.gradle.nexus.infra.SimplifiedHttpJsonRestClient
import io.codearte.gradle.nexus.logic.OperationRetrier
import io.codearte.gradle.nexus.logic.RepositoryCloser
import io.codearte.gradle.nexus.logic.RepositoryCreator
import io.codearte.gradle.nexus.logic.RepositoryDropper
import io.codearte.gradle.nexus.logic.RepositoryFetcher
import io.codearte.gradle.nexus.logic.RepositoryReleaser
import io.codearte.gradle.nexus.logic.RepositoryState
import io.codearte.gradle.nexus.logic.RepositoryStateFetcher
import io.codearte.gradle.nexus.logic.RetryingRepositoryTransitioner
import io.codearte.gradle.nexus.logic.StagingProfileFetcher
import nebula.test.functional.ExecutionResult
import okhttp3.OkHttpClient
import spock.lang.Ignore
import spock.lang.Stepwise

//TODO: Duplication with BasicFunctionalSpec done at Gradle level - decide which tests are better/easier to use and maintain
@Stepwise
class ExploratoryE2ESpec extends BaseNexusStagingFunctionalSpec implements E2ESpecHelperTrait {

    private SimplifiedHttpJsonRestClient client
    private RepositoryStateFetcher repoStateFetcher
    private OperationRetrier<RepositoryState> retrier

    private static String resolvedStagingRepositoryId

    def setup() {
        client = new SimplifiedHttpJsonRestClient(new OkHttpClient(), nexusUsernameAT, nexusPasswordAT)
        repoStateFetcher = new RepositoryStateFetcher(client, E2E_SERVER_BASE_PATH)
        retrier = new OperationRetrier<>()
    }

    @NotYetImplemented
    def "remove all staging repositories if exist as clean up"() {}

    //TODO: Remove "should" prefix, it's default by convention
    def "should get staging profile id from server e2e"() {
        given:
            StagingProfileFetcher fetcher = new StagingProfileFetcher(client, E2E_SERVER_BASE_PATH)
        when:
            String stagingProfileId = fetcher.getStagingProfileIdForPackageGroup(E2E_PACKAGE_GROUP)
        then:
            stagingProfileId == E2E_STAGING_PROFILE_ID
    }

    def "should create staging repository explicitly e2e"() {
        given:
            RepositoryCreator creator = new RepositoryCreator(client, E2E_SERVER_BASE_PATH)
        when:
            String stagingRepositoryId = creator.createStagingRepositoryAndReturnId(E2E_STAGING_PROFILE_ID)
        then:
            println stagingRepositoryId
            stagingRepositoryId.startsWith("iogitlabnexus-at")
        and:
            propagateStagingRepositoryIdToAnotherTest(stagingRepositoryId)
    }

    def "should upload artifacts to server e2e"() {
        given:
            assert resolvedStagingRepositoryId
        and:
            copyResources("sampleProjects//nexus-at-minimal", "")
        when:
            ExecutionResult result = runTasksSuccessfully('uploadArchives',
                "-Pe2eRepositoryUrl=${E2E_SERVER_BASE_PATH}staging/deployByRepositoryId/${resolvedStagingRepositoryId}")
        then:
            result.standardOutput.contains('Uploading: io/gitlab/nexus-at/minimal/nexus-at-minimal/')
    }

    //TODO: Adjust to (optionally) just get repository ID in getNonTransitioningRepositoryStateById()
    @Ignore("Not executed by default as explicit stagingRepositoryId should used for parallel test execution on CI")
    def "should get open repository id from server e2e"() {
        given:
            RepositoryFetcher fetcher = new RepositoryFetcher(client, E2E_SERVER_BASE_PATH)
        when:
            String stagingRepositoryId = fetcher.getRepositoryIdWithGivenStateForStagingProfileId(E2E_STAGING_PROFILE_ID, RepositoryState.OPEN)
        then:
            println stagingRepositoryId
            stagingRepositoryId == resolvedStagingRepositoryId
    }

    def "should get not in transition open repository state by repository id from server e2e"() {
        given:
            assert resolvedStagingRepositoryId
        when:
            RepositoryState receivedRepoState = repoStateFetcher.getNonTransitioningRepositoryStateById(resolvedStagingRepositoryId)
        then:
            receivedRepoState == RepositoryState.OPEN
    }

    def "should close open repository waiting for transition to finish e2e"() {
        given:
            assert resolvedStagingRepositoryId
        and:
            RepositoryCloser closer = new RepositoryCloser(client, E2E_SERVER_BASE_PATH, E2E_REPOSITORY_DESCRIPTION)
            RetryingRepositoryTransitioner retryingCloser = new RetryingRepositoryTransitioner(closer, repoStateFetcher, retrier)
        when:
            retryingCloser.performWithRepositoryIdAndStagingProfileId(resolvedStagingRepositoryId, E2E_STAGING_PROFILE_ID)
        then:
            noExceptionThrown()
        and:
            RepositoryState receivedRepoState = repoStateFetcher.getNonTransitioningRepositoryStateById(resolvedStagingRepositoryId)
        then:
            receivedRepoState == RepositoryState.CLOSED
    }

    @Ignore('Not the base path')
    def "should drop open repository e2e"() {
        given:
            assert resolvedStagingRepositoryId
        and:
            RepositoryDropper dropper = new RepositoryDropper(client, E2E_SERVER_BASE_PATH, E2E_REPOSITORY_DESCRIPTION)
            RetryingRepositoryTransitioner retryingDropper = new RetryingRepositoryTransitioner(dropper, repoStateFetcher, retrier)
        when:
            retryingDropper.performWithRepositoryIdAndStagingProfileId(resolvedStagingRepositoryId, E2E_STAGING_PROFILE_ID)
        then:
            noExceptionThrown()
        when:
            RepositoryState receivedRepoState = repoStateFetcher.getNonTransitioningRepositoryStateById(resolvedStagingRepositoryId)
        then:
            receivedRepoState == RepositoryState.NOT_FOUND
    }

    def "should release closed repository e2e"() {
        given:
            assert resolvedStagingRepositoryId
        and:
            RepositoryReleaser releaser = new RepositoryReleaser(client, E2E_SERVER_BASE_PATH, E2E_REPOSITORY_DESCRIPTION)
            RetryingRepositoryTransitioner retryingReleaser = new RetryingRepositoryTransitioner(releaser, repoStateFetcher, retrier)
        when:
            retryingReleaser.performWithRepositoryIdAndStagingProfileId(resolvedStagingRepositoryId, E2E_STAGING_PROFILE_ID)
        then:
            noExceptionThrown()
    }

    def "repository after release should be dropped immediately e2e"() {
        given:
            assert resolvedStagingRepositoryId
        when:
            RepositoryState receivedRepoState = repoStateFetcher.getNonTransitioningRepositoryStateById(resolvedStagingRepositoryId)
        then:
            receivedRepoState == RepositoryState.NOT_FOUND
    }

    private void propagateStagingRepositoryIdToAnotherTest(String stagingRepositoryId) {
        resolvedStagingRepositoryId = stagingRepositoryId
    }
}
