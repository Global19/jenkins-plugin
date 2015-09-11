package com.openshift.openshiftjenkinsbuildutils;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import com.openshift.restclient.ClientFactory;
import com.openshift.restclient.IClient;
import com.openshift.restclient.ISSLCertificateCallback;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.authorization.TokenAuthorizationStrategy;
import com.openshift.restclient.capability.ICapability;
import com.openshift.restclient.model.IReplicationController;

import javax.net.ssl.SSLSession;
import javax.servlet.ServletException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * OpenShift {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link OpenShiftScaler} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #name})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform(AbstractBuild, Launcher, BuildListener)}
 * method will be invoked. 
 *
 * @author Gabe Montero
 */
public class OpenShiftScaler extends Builder implements ISSLCertificateCallback {

    private String apiURL = "https://openshift.default.svc.cluster.local";
    private String depCfg = "frontend";
    private String nameSpace = "test";
    private String replicaCount = "0";
    private String authToken = "";
    
    
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public OpenShiftScaler(String apiURL, String depCfg, String nameSpace, String replicaCount, String authToken) {
        this.apiURL = apiURL;
        this.depCfg = depCfg;
        this.nameSpace = nameSpace;
        this.replicaCount = replicaCount;
        this.authToken = authToken;
    }

    /**
     * We'll use this from the <tt>config.jelly</tt>.
     */
    public String getApiURL() {
		return apiURL;
	}

	public String getDepCfg() {
		return depCfg;
	}

	public String getNameSpace() {
		return nameSpace;
	}
	
	public String getReplicaCount() {
		return replicaCount;
	}
	
	public String getAuthToken() {
		return authToken;
	}

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
    	System.setProperty(ICapability.OPENSHIFT_BINARY_LOCATION, Constants.OC_LOCATION);
    	listener.getLogger().println("OpenShiftScaler in perform");
    	
    	// obtain auth token from defined spot in OpenShift Jenkins image
    	authToken = Auth.deriveAuth(authToken, listener);
    	    	
    	// get oc client (sometime REST, sometimes Exec of oc command
    	IClient client = new ClientFactory().create(apiURL, this);
    	
    	if (client != null) {
    		// seed the auth
        	client.setAuthorizationStrategy(new TokenAuthorizationStrategy(this.authToken));
        	
        	String depId = null;
        	IReplicationController rc = null;
        	// find corresponding rep ctrl and scale it to the right value
        	long currTime = System.currentTimeMillis();
        	// in testing with the jenkins-ci sample, the initial deploy after
        	// a build is kinda slow ... gotta wait more than one minute
        	while (System.currentTimeMillis() < (currTime + 180000)) {
            	// get ReplicationController ref
            	Map<String, IReplicationController> rcs = Deployment.getDeployments(client, nameSpace, listener);
            	
            	for (String key : rcs.keySet()) {
            		rc = rcs.get(key);
            		if (key.startsWith(depCfg)) {
            			depId = key;
            			listener.getLogger().println("OpenShiftScaler key into oc scale is " + depId);
                		listener.getLogger().println("OpenShiftScaler rep ctrl " + rc);
            			
    					//TODO assume there is only 1 dep cfg per build
    					break;
            		}
            	}
            	
            	// if they want to scale down to 0 and there is not rc to begin with, just punt / consider a no-op
            	if (depId != null || replicaCount.equals("0"))
            		break;
            	else {
					listener.getLogger().println("OpenShiftScaler wait 10 seconds, then look for rep ctrl again");
					try {
						Thread.sleep(10000);
					} catch (InterruptedException e) {
					}
            	}
        	}
        	
        	if (depId == null) {
        		listener.getLogger().println("OpenShiftScaler did not find any replication controllers for " + depCfg);
        		//TODO if not found, and we are scaling down to zero, don't consider an error - this may be safety
        		// measure to scale down if exits ... perhaps we make this behavior configurable over time, but for now.
        		// we refrain from adding yet 1 more config option
        		if (replicaCount.equals("0"))
        			return true;
        		else
        			return false;
        	}
        	
        	// do the oc scale ... may need to retry
        	currTime = System.currentTimeMillis();
        	boolean scaleDone = false;
        	while (System.currentTimeMillis() < (currTime + 60000)) {
    			BinaryScaleInvocation runner = new BinaryScaleInvocation(replicaCount, depId, nameSpace, client);
    			InputStream logs = null;
				// create stream and copy bytes
				try {
					logs = new BufferedInputStream(runner.getLogs(true));
					int b;
					while ((b = logs.read()) != -1) {
						listener.getLogger().write(b);
					}
					scaleDone = true;
				} catch (Throwable e) {
					e.printStackTrace(listener.getLogger());
				} finally {
					runner.stop();
					try {
						if (logs != null)
							logs.close();
					} catch (Throwable e) {
						e.printStackTrace(listener.getLogger());
					}
				}
				
				if (logs != null) {
					break;
				} else {
					listener.getLogger().println("OpenShiftScaler wait 10 seconds, then try oc scale again");
					try {
						Thread.sleep(10000);
					} catch (InterruptedException e) {
					}
				}
        	}
        	
        	if (!scaleDone) {
        		listener.getLogger().println("OpenShiftScaler could not get oc scale executed");
        		return false;
        	}
        	
        	// if it exists, confirm rep ctrl is scaled down to where it is suppose to be
        	int count = 0;
        	boolean scaledAppropriately = false;
        	while (count < 5) {
        		rc = client.get(ResourceKind.REPLICATION_CONTROLLER, depId, nameSpace);
        		if (rc == null) {
        			listener.getLogger().println("OpenShiftScaler rep ctrl " + depId + " disappeared !!");
        			return false;
        		}
        		
        		listener.getLogger().println("OpenShiftScaler current replica count " + rc.getCurrentReplicaCount());
        		
        		if (rc.getCurrentReplicaCount() == Integer.decode(replicaCount)) {
        			scaledAppropriately = true;
        			break;
        		}
        		try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}
        	}
        	
        	if (scaledAppropriately)
        		return true;
        	
    	} else {
    		listener.getLogger().println("OpenShiftScaler could not get oc client");
    		return false;
    	}

    	return false;
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link OpenShiftScaler}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the various fields.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckApiURL(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set apiURL");
            return FormValidation.ok();
        }

        public FormValidation doCheckDepCfg(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set depCfg");
            return FormValidation.ok();
        }

        public FormValidation doCheckReplicaCount(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set replicaCount");
            try {
            	Integer.decode(value);
            } catch (NumberFormatException e) {
            	return FormValidation.error("Please specify an integer for replicaCount");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckNameSpace(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set nameSpace");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Scale deployments in OpenShift";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // pull info from formData, set appropriate instance field (which should have a getter), and call save().
            save();
            return super.configure(req,formData);
        }

    }

	@Override
	public boolean allowCertificate(X509Certificate[] chain) {
		return true;
	}

	@Override
	public boolean allowHostname(String hostname, SSLSession session) {
		return true;
	}
}

