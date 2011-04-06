package at.irregular.jenkins.plugins;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AutoCompletionCandidates;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Queue;
import hudson.model.Queue.Executable;
import hudson.scm.PollingResult;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.FormValidation;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class PreBuildJob extends BuildWrapper {

	/**
	 * The project that should be built before the current project
	 */
	private String project;
	
	/**
	 * If true the project to build before the current is only built if it has scm changes 
	 */
	private Boolean poll;
	
	/**
	 * If true the build of the current project waits until the other build finished
	 */
	private Boolean wait = true;
	
	@DataBoundConstructor
	public PreBuildJob(String project, Boolean poll, Boolean wait) throws FormException {
		this.project = project;
		this.poll = poll;
		this.wait = wait;
	}
	
	/**
	 * The setUp method is used to execute the pre build.
	 * 
	 * If poll is enabled, the build is only executed if the scm has changes.
	 * If wait is enabled, the build waits until the pre build is finished.
	 * Using wait blocks the current executor until the pre build is finished!
	 * When wait is enabled and the pre build fails, also this build fails.
	 */
	@Override
	public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
		Environment environment = new Environment() { };
		Hudson hudson = Hudson.getInstance();
		PrintStream logger = listener.getLogger();
		AbstractProject<?, ? extends AbstractBuild<?, ?>> project = Hudson.getInstance().getItemByFullName(this.project, AbstractProject.class);
		
		String error = null;
		if(project == null) {
			// the project must exist
			error = "[PreBuildJob] The project \"" + this.project + "\" doesn't exist but should be built before this project.";
		} else if (project.getFullName().equals(build.getProject().getFullName())) {
			// can't build ourself as pre build
			error = "[PreBuildJob] The project is configured to build itself before.";
		} else if (! project.isBuildable()) {
			// the project is not buildable 
			error = "[PreBuildJob] The project \"" + this.project + "\" is not buildable.";
		} else if(hudson.getNumExecutors() < 2 && this.wait) {
			// if we only have one executor we would run into a deadlock
			error = "[PreBuildJob] If you want to use a pre build job which waits for the job to finish you need at least two executors.";
		} else {
			// TODO implement loop check (if other pre build project references this project for pre build)
			// How to get the BuildWrappers for a project?
		}
		
		if(error != null) {
			throw new IOException(error);
		}
		
		Future<? extends Executable> future = null;
		if (! project.isInQueue()) {
			// Project is currently not in queue
			logger.println("[PreBuildJob] The pre build project is not building currently.");
			if (this.poll) {
				logger.println("[PreBuildJob] Polling the SCM of the pre build project.");
				PollingResult result = project.poll(listener);
				if (result.hasChanges()) {
					logger.println("[PreBuildJob] SCM has changes - building the project.");
					future = project.scheduleBuild2(0);
				} else {
					logger.println("[PreBuildJob] SCM has no changes - not building the project.");
					return environment;
				}
			} else {
				logger.println("[PreBuildJob] Building the pre build project \"" + this.project + "\".");
				future = project.scheduleBuild2(0);
			}
		} else {
			// The project is currently in the queue get the future for it
			logger.println("[PreBuildJob] The pre build project is queued currently waiting for it to finish.");
			Queue queue = hudson.getQueue();
			Queue.Item item = queue.getItem(project);
			if (item != null) {
				future = item.getFuture();
			}
		}
		
		if (this.wait) {
			// Waiting for the future
			logger.println("[PreBuildJob] Waiting for the project \"" + this.project + "\" to finish the build.");
			try {
				if (future != null) {
					AbstractBuild<?, ?> resultBuild = (AbstractBuild<?,?>) future.get(); // wait for the job to finish
					// The result build failed
					if(! resultBuild.getResult().isBetterThan(Result.FAILURE)) {
						throw new IOException("[PreBuildJob] The pre build project failed to build.");
					}
				}
			} catch (ExecutionException e) {
				throw new IOException("[PreBuildJob] The pre build project failed to build.");
			}
		}
		
		return environment;
	}
	
	@Extension
	public static final class DescriptorImpl extends BuildWrapperDescriptor {
		/**
		 * Display name for the project configuration
		 */
		@Override
		public String getDisplayName() {
			return "Build another project before this build";
		}
		
		/**
		 * Form validation for the project field
		 */
        public FormValidation doCheckProject(@QueryParameter String value) {
			String projectName = value.trim();
			if (StringUtils.isNotEmpty(projectName)) {
				Item item = Hudson.getInstance().getItemByFullName(projectName, AbstractProject.class);
				if (item == null) {
					return FormValidation.error("No such project");
				}
			}

			return FormValidation.ok();
        }
		
        /**
         * Autocompletion for the project field
         */
        @SuppressWarnings("rawtypes")
        public AutoCompletionCandidates doAutoCompleteProject(@QueryParameter String value) {
            AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            List<AbstractProject> projects = Hudson.getInstance().getItems(AbstractProject.class);
            for (AbstractProject project : projects) {
                if (project.getFullName().startsWith(value)) {
                    candidates.add(project.getFullName());
                }
            }
            return candidates;
        }
        
		@Override
		public boolean isApplicable(AbstractProject<?, ?> item) {
			return true;
		}
	}
	
	public String getProject() {
		return project;
	}

	public void setProject(String project) {
		this.project = project;
	}

	public Boolean getPoll() {
		return poll;
	}
	
	public void setPoll(Boolean poll) {
		this.poll = poll;
	}

	public Boolean getWait() {
		return wait;
	}

	public void setWait(Boolean wait) {
		this.wait = wait;
	}
}
