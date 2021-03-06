package com.cloudbees.jenkins.plugins;

import hudson.model.Job;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitStatus;
import hudson.plugins.mercurial.MercurialSCM;
import hudson.plugins.mercurial.MercurialSCMSource;
import hudson.scm.SCM;
import hudson.security.ACL;

import java.net.URISyntaxException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


import jenkins.model.Jenkins;

import jenkins.model.ParameterizedJobMixIn;
import jenkins.plugins.git.GitSCMSource;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.triggers.SCMTriggerItem;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import com.google.common.base.Objects;

public class BitbucketJobProbe {

    @Deprecated
    public void triggerMatchingJobs(String user, String url, String scm) {
        triggerMatchingJobs(user, url, scm, "");
    }

    public void triggerMatchingJobs(String user, String url, String scm, String payload) {
        if ("git".equals(scm) || "hg".equals(scm)) {
            SecurityContext old = Jenkins.getInstance().getACL().impersonate(ACL.SYSTEM);
            try {
                URIish remote = new URIish(url);
                for (Job<?, ?> job : Jenkins.getInstance().getAllItems(Job.class)) {
                    BitBucketTrigger bTrigger = null;
                    LOGGER.log(Level.FINE, "Considering candidate job {0}", job.getName());

                    if (job instanceof ParameterizedJobMixIn.ParameterizedJob) {
                        ParameterizedJobMixIn.ParameterizedJob pJob = (ParameterizedJobMixIn.ParameterizedJob) job;
                        for (Object trigger : pJob.getTriggers().values()) {
                            if (trigger instanceof BitBucketTrigger) {
                                bTrigger = (BitBucketTrigger) trigger;
                                break;
                            }
                        }
                    }
                    if (bTrigger != null) {
                        LOGGER.log(Level.FINE, "Considering to poke {0}", job.getFullDisplayName());
                        SCMTriggerItem item = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(job);
                        if (item == null) {
                            LOGGER.log(Level.INFO, "item is null");
                        } else {
                            List<SCM> scmTriggered = new ArrayList<>();
                            if (item.getSCMs().isEmpty()) {
                                LOGGER.log(Level.WARNING, "No SCM configuration was found!");
                            }
                            for (SCM scmTrigger : item.getSCMs()) {
                                if (match(scmTrigger, remote, bTrigger.getOverrideUrl()) && !hasBeenTriggered(scmTriggered, scmTrigger)) {
                                    LOGGER.log(Level.INFO, "Triggering BitBucket job {0}", job.getFullDisplayName());
                                    scmTriggered.add(scmTrigger);
                                    bTrigger.onPost(user, payload);
                                } else {
                                    LOGGER.log(Level.FINEST, "{0} SCM doesn't match remote repo {1}", new Object[]{job.getName(), remote});
                                }
                            }
                        }
                    } else
                        LOGGER.log(Level.FINE, "{0} hasn't BitBucketTrigger set", job.getName());
                }
                LOGGER.log(Level.FINE, "Now checking SCMSourceOwners/multiBranchProjects");
                for (SCMSourceOwner scmSourceOwner : Jenkins.getInstance().getAllItems(SCMSourceOwner.class)) {
                    LOGGER.log(Level.FINE, "Considering candidate scmSourceOwner {0}", scmSourceOwner.getFullDisplayName());
                    List<SCMSource> scmSources = scmSourceOwner.getSCMSources();
                    for (SCMSource scmSource : scmSources) {
                        LOGGER.log(Level.FINER, "Considering candidate scmSource {0}", scmSource);
                        if (match(scmSource, remote)) {
                            LOGGER.log(Level.INFO, "Triggering BitBucket scmSourceOwner {0}", scmSourceOwner);
                            scmSourceOwner.onSCMSourceUpdated(scmSource);
                        } else {
                            LOGGER.log(Level.FINE, String.format("SCM [%s] doesn't match remote repo [%s]", scmSourceOwner.getFullDisplayName(), remote));
                        }
                    }
                }
            } catch (URISyntaxException e) {
                LOGGER.log(Level.WARNING, "Invalid repository URL {0}", url);
            } finally {
                SecurityContextHolder.setContext(old);
            }

        } else {
            throw new UnsupportedOperationException("Unsupported SCM type " + scm);
        }
    }

    private boolean hasBeenTriggered(List<SCM> scmTriggered, SCM scmTrigger) {
        for (SCM scm : scmTriggered) {
            if (scm.equals(scmTrigger)) {
                return true;
            }
        }
        return false;
    }

    private boolean match(SCM scm, URIish url, String overrideUrl) {
        if (scm instanceof GitSCM) {
            LOGGER.log(Level.FINE, "SCM is instance of GitSCM");
            for (RemoteConfig remoteConfig : ((GitSCM) scm).getRepositories()) {
                for (URIish urIish : remoteConfig.getURIs()) {
                    // needed cause the ssh and https URI differs in Bitbucket Server.
                    if (urIish.getPath().startsWith("/scm")) {
                        urIish = urIish.setPath(urIish.getPath().substring(4));
                    }

                    // needed because bitbucket self hosted does not transfer any host information
                    if (StringUtils.isEmpty(url.getHost())) {
                        urIish = urIish.setHost(url.getHost());
                    }

                    LOGGER.log(Level.FINE, "Trying to match {0} ", urIish.toString() + "<-->" + url.toString());
                    if (GitStatus.looselyMatches(urIish, url)) {
                        return true;
                    } else if (overrideUrl != null && !overrideUrl.isEmpty()) {
                        LOGGER.log(Level.FINE, "Trying to match using override Repository URL {0} ", overrideUrl + "<-->" + url.toString());
                        return overrideUrl.contentEquals(url.toString());
                    }
                }
            }
        } else if (scm instanceof MercurialSCM) {
            LOGGER.log(Level.FINEST, "SCM is instance of MercurialSCM");
            try {
                URI hgUri = new URI(((MercurialSCM) scm).getSource());
                String remote = url.toString();
                if (looselyMatches(hgUri, remote)) {
                    return true;
                }
            } catch (URISyntaxException ex) {
                LOGGER.log(Level.SEVERE, "Could not parse jobSource uri: {0} ", ex);
            }
        } else {
            LOGGER.log(Level.FINEST, "SCM is instance of [" + scm.getClass().getSimpleName() + "] which is not supported");
        }

        return false;
    }

    private boolean match(SCMSource scm, URIish url) {
        if (scm instanceof GitSCMSource) {
            LOGGER.log(Level.FINEST, "SCMSource is GitSCMSource");
            String gitRemote = ((GitSCMSource) scm).getRemote();
            URIish urIish;
            try {
                urIish = new URIish(gitRemote);
            } catch (URISyntaxException e) {
                LOGGER.log(Level.SEVERE, "Could not parse gitRemote: " + gitRemote, e);
                return false;
            }
            // needed cause the ssh and https URI differs in Bitbucket Server.
            if (urIish.getPath().startsWith("/scm")) {
                urIish = urIish.setPath(urIish.getPath().substring(4));
            }

            // needed because bitbucket self hosted does not transfer any host information
            if (StringUtils.isEmpty(url.getHost())) {
                urIish = urIish.setHost(url.getHost());
            }

            LOGGER.log(Level.FINE, "Trying to match {0} ", urIish.toString() + "<-->" + url.toString());
            if (GitStatus.looselyMatches(urIish, url)) {
                return true;
            }
        } else if (scm instanceof MercurialSCMSource) {
            LOGGER.log(Level.FINEST, "SCMSource is MercurialSCMSource");
            try {
                URI hgUri = new URI(((MercurialSCMSource) scm).getSource());
                String remote = url.toString();
                if (looselyMatches(hgUri, remote)) {
                    return true;
                }
            } catch (URISyntaxException ex) {
                LOGGER.log(Level.SEVERE, "Could not parse jobSource uri: {0} ", ex);
            }
        } else {
            LOGGER.log(Level.FINEST, "SCMSource is [" + scm.getClass().getSimpleName() + "] which is not supported");
        }
        return false;
    }

    private boolean looselyMatches(URI notifyUri, String repository) {
        boolean result = false;
        try {
            URI repositoryUri = new URI(repository);
            result = Objects.equal(notifyUri.getHost(), repositoryUri.getHost())
                    && Objects.equal(notifyUri.getPath(), repositoryUri.getPath())
                    && Objects.equal(notifyUri.getQuery(), repositoryUri.getQuery());
        } catch (URISyntaxException ex) {
            LOGGER.log(Level.SEVERE, "Could not parse repository uri: {0}, {1}", new Object[]{repository, ex});
        }
        return result;
    }

    private static final Logger LOGGER = Logger.getLogger(BitbucketJobProbe.class.getName());

}
