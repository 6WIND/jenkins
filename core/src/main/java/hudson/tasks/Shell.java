/*
 * The MIT License
 * 
 * Copyright (c) 2004-2011, Sun Microsystems, Inc., Kohsuke Kawaguchi, Jene Jasper, Yahoo! Inc., Seiji Sogabe
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.tasks;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Proc.LocalProc;
import hudson.Util;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.AbstractProject;
import hudson.remoting.VirtualChannel;
import hudson.util.FormValidation;
import hudson.util.ProcessTree;
import java.io.IOException;
import java.io.ObjectStreamException;
import hudson.util.LineEndingConversion;
import jenkins.security.MasterToSlaveCallable;
import net.sf.json.JSONObject;
import org.apache.commons.lang.SystemUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;

/**
 * Executes a series of commands by using a shell.
 *
 * @author Kohsuke Kawaguchi
 */
public class Shell extends CommandInterpreter {

    private String signalForAbort;
    private String timeoutForAbort;
    private String maxKillDepthForAbort;

    @DataBoundConstructor
    public Shell(String command,
                 String signalForAbort,
                 String timeoutForAbort,
                 String maxKillDepthForAbort) {
        super(LineEndingConversion.convertEOL(command, LineEndingConversion.EOLType.Unix));
        this.signalForAbort = Util.fixEmptyAndTrim(signalForAbort);
        this.timeoutForAbort = Util.fixEmptyAndTrim(timeoutForAbort);
        this.maxKillDepthForAbort = Util.fixEmptyAndTrim(maxKillDepthForAbort);
    }

    /* for backward compat */
    public Shell(String command) {
        this(command, null, null, null);
    }

    private Integer unstableReturn;



    /**
     * Older versions of bash have a bug where non-ASCII on the first line
     * makes the shell think the file is a binary file and not a script. Adding
     * a leading line feed works around this problem.
     */
    private static String addLineFeedForNonASCII(String s) {
        if(!s.startsWith("#!")) {
            if (s.indexOf('\n')!=0) {
                return "\n" + s;
            }
        }

        return s;
    }

    public String[] buildCommandLine(FilePath script) {
        if(command.startsWith("#!")) {
            // interpreter override
            int end = command.indexOf('\n');
            if(end<0)   end=command.length();
            List<String> args = new ArrayList<String>();
            args.addAll(Arrays.asList(Util.tokenize(command.substring(0,end).trim())));
            args.add(script.getRemote());
            args.set(0,args.get(0).substring(2));   // trim off "#!"
            return args.toArray(new String[args.size()]);
        } else
            return new String[] { getDescriptor().getShellOrDefault(script.getChannel()), "-xe", script.getRemote()};
    }

    protected String getContents() {
        return addLineFeedForNonASCII(LineEndingConversion.convertEOL(command,LineEndingConversion.EOLType.Unix));
    }

    protected String getFileExtension() {
        return ".sh";
    }

    @CheckForNull
    public final Integer getUnstableReturn() {
        return new Integer(0).equals(unstableReturn) ? null : unstableReturn;
    }

    @DataBoundSetter
    public void setUnstableReturn(Integer unstableReturn) {
        this.unstableReturn = unstableReturn;
    }

    @Override
    protected boolean isErrorlevelForUnstableBuild(int exitCode) {
        return this.unstableReturn != null && exitCode != 0 && this.unstableReturn.equals(exitCode);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    private Object readResolve() throws ObjectStreamException {
        Shell shell = new Shell(command, signalForAbort, timeoutForAbort, maxKillDepthForAbort);
        shell.setUnstableReturn(unstableReturn);
        return shell;
    }

    public String getSignalForAbort() {
        return signalForAbort;
    }

    public void setSignalForAbort(String signalForAbort) {
        this.signalForAbort = signalForAbort;
    }

    public String getTimeoutForAbort() {
        return timeoutForAbort;
    }

    public void setTimeoutForAbort(String timeoutForAbort) {
        this.timeoutForAbort = timeoutForAbort;
    }

    public String getMaxKillDepthForAbort() {
        return maxKillDepthForAbort;
    }

    public void setMaxKillDepthForAbort(String maxKillDepthForAbort) {
        this.maxKillDepthForAbort = maxKillDepthForAbort;
    }

    @Override
    public int runCommand(Launcher launcher, TaskListener listener,
            FilePath ws, FilePath script, EnvVars envVars) throws IOException,
            InterruptedException {
        ProcStarter starter = launcher.launch();
        starter.cmds(buildCommandLine(script));
        starter.envs(envVars);
        starter.stdout(listener);
        starter.pwd(ws);

        try {
            String defaultSignal = Util.fixEmptyAndTrim(getDescriptor().getDefaultSignal());
            String defaultTimeout = Util.fixEmptyAndTrim(getDescriptor().getDefaultTimeout());
            String defaultDepth = Util.fixEmptyAndTrim(getDescriptor().getDefaultMaxKillDepth());

            /* By default, if nothing is configured, keep the legacy procedure for killing process */
            int signal = LocalProc.SIG_FOR_ABORT_NOT_CONFIGURED;
            int timeout = LocalProc.DEFAULT_TIMEOUT;
            int depth = LocalProc.DEFAULT_MAX_DEPTH_FOR_ABORT;

            /* Default values for signal and timeout defined in Jenkins global properties */
            if (defaultSignal != null)
                signal = Integer.decode(defaultSignal);

            if (defaultTimeout != null)
                timeout = Integer.decode(defaultTimeout);

            if (defaultDepth != null)
                depth = Integer.decode(defaultDepth);

            /* signal and timeout can be overidden by jobs */
            if (signalForAbort != null)
                signal = Integer.decode(signalForAbort);

            if (timeoutForAbort != null)
                timeout = Integer.decode(timeoutForAbort);

            if (maxKillDepthForAbort != null)
                depth = Integer.decode(maxKillDepthForAbort);

            starter.setSignalForAbort(signal);
            starter.setAbortTimeout(timeout);
            starter.setMaxKillDepthForAbort(depth);
        } catch (NumberFormatException e) {
            /* ignore */
        }

        return join(starter.start());
    }

    @Extension @Symbol("shell")
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * Shell executable, or null to default.
         */
        private String shell;
        private String defaultSignal;
        private String defaultTimeout;
        private String defaultMaxKillDepth;

        public DescriptorImpl() {
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public String getShell() {
            return shell;
        }

        /**
         *  @deprecated 1.403
         *      Use {@link #getShellOrDefault(hudson.remoting.VirtualChannel) }.
         */
        @Deprecated
        public String getShellOrDefault() {
            if (shell == null) {
                return SystemUtils.IS_OS_WINDOWS ? "sh" : "/bin/sh";
            }
            return shell;
        }

        public String getShellOrDefault(VirtualChannel channel) {
            if (shell != null)
                return shell;

            String interpreter = null;
            try {
                interpreter = channel.call(new Shellinterpreter());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, null, e);
            } catch (InterruptedException e) {
                LOGGER.log(Level.WARNING, null, e);
            }
            if (interpreter == null) {
                interpreter = getShellOrDefault();
            }

            return interpreter;
        }

        public void setShell(String shell) {
            this.shell = Util.fixEmptyAndTrim(shell);
            save();
        }

        public String getDisplayName() {
            return Messages.Shell_DisplayName();
        }

        public String getDefaultSignal() {
            return defaultSignal;
        }

        public void setDefaultSignal(String defaultSignal) {
            this.defaultSignal = defaultSignal;
        }

        public String getDefaultTimeout() {
            return defaultTimeout;
        }

        public void setDefaultTimeout(String defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
        }

        public String getDefaultMaxKillDepth() {
            return defaultMaxKillDepth;
        }

        public void setDefaultMaxKillDepth(String defaultMaxKillDepth) {
            this.defaultMaxKillDepth = defaultMaxKillDepth;
        }

        /**
         * Performs on-the-fly validation of the exit code.
         */
        @Restricted(DoNotUse.class)
        public FormValidation doCheckUnstableReturn(@QueryParameter String value) {
            value = Util.fixEmptyAndTrim(value);
            if (value == null) {
                return FormValidation.ok();
            }
            long unstableReturn;
            try {
                unstableReturn = Long.parseLong(value);
            } catch (NumberFormatException e) {
                return FormValidation.error(hudson.model.Messages.Hudson_NotANumber());
            }
            if (unstableReturn == 0) {
                return FormValidation.warning(hudson.tasks.Messages.Shell_invalid_exit_code_zero());
            }
            if (unstableReturn < 1 || unstableReturn > 255) {
                return FormValidation.error(hudson.tasks.Messages.Shell_invalid_exit_code_range(unstableReturn));
            }
            return FormValidation.ok();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject data) throws FormException {
            req.bindJSON(this, data);
            return super.configure(req, data);
        }

        /**
         * Check the existence of sh in the given location.
         */
        public FormValidation doCheckShell(@QueryParameter String value) {
            // Executable requires admin permission
            return FormValidation.validateExecutable(value);
        }

        private static final class Shellinterpreter extends MasterToSlaveCallable<String, IOException> {

            private static final long serialVersionUID = 1L;

            public String call() throws IOException {
                return SystemUtils.IS_OS_WINDOWS ? "sh" : "/bin/sh";
            }
        }

    }

    private static final Logger LOGGER = Logger.getLogger(Shell.class.getName());
}
