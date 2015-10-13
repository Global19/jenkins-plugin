# openshift-jenkins-buildutils
As set of Jenkins plugins that operate on OpenShift.

The set includes these Jenkins "build steps", selectable from the `Add build step` pull down available on any project's configure page:

1) "Perform builds in OpenShift": performs the equivalent of an `oc start-build` command invocation, where the build logs are echoed to the Jenkins plugin in real time; in addition to confirming whether the build succeeded or not, the plugin will look to see if a deployment config specifying a non-zero replica count existed, and if so, confirm whether the deployment occurred

2) "Scale deployments in OpenShift":  performs the equivalent of an `oc scale` command invocation; the number of desired replicas is specified as a parameter to this build step, and the plugin will confirm whether the desired number of replicas was launched in a timely manner; if no integer is provided, it will assume 0 replica pods are desired

3) "Trigger a deployment in OpenShift":  performs the equivalent of an `oc deploy` command invocation; the plugin will confirm whether the desired number of replicas was launched in a timely manner

4) "Verify a service is up in OpenShift": finds the ip and port for the specified OpenShift service, and attempts to make a HTTP connection to that ip/port combination to confirm the service is up

5) "Tag an image in OpenShift": performs the equivalent of an `oc tag` command invocation in order to manipulate tags for images in OpenShift ImageStream's

6) "Verify deployments in OpenShift":  determines whether the expected set of DeploymentConfig's, ReplicationController's, and active replicas are present based on prior use of either the "Scale deployments in OpenShift" (2) or "Trigger a deployment in OpenShift" (3) steps; its activities specifically include:

   - it first confirms the specified deployment config exists
   - it then gets the list of all replication controllers for that DC, and finds the latest incarnation
   - and then for up to 3 minutes, it sees if the current replica count is at least equal to the desired replica count.


7) "Get latest OpenShift build status":  performs the equivalent of an 'oc get builds` command invocation for the provided buildConfig key provided; once the list of builds are obtained, the state of the latest build is inspected for up to a minute to see if it has completed successfully; this build step is intended to allow for monitoring of builds either generated internally or externally from the Jenkins Job configuration housing the build step

8) "Monitor OpenShift ImageStreams": rather than a Build step extension plugin, this is an extension of the Jenkis SCM plugin, where this baked-in polling mechanism provided by Jenkins is leveraged by exposing some of the common semantics between OpenShift ImageStreams (which are abstractions of Docker repositories) and SCMs - versions / commit IDs of related artifacts (images vs. programmatics files); when the specific tags configured changes (as reflected by updated commit IDs) are reported to Jenkins through the SCM plugin contract, Jenkins will initiate a build for the Job configuration in question. Note, there are no extractions of source code to workspaces provided by Jenkins to the SCMs.  It is expected that the build steps in the associated job configuration will initiate any OpenShift related activities that were dependent on the ImageStream resource being monitored.

9) "Check Deployment Success":  a verification step geared specifically to the "Trigger a deployment in OpenShift" (3) step; it differs from the more generic "Verify deployments in OpenShift" (6) in that it confirms that the latest associated image is running before claiming success

10) "Create resource(s) in OpenShift":  performs the equivalent of an `oc create` command invocation; this build step takes in the provided JSON or YAML text, and if it conforms to OpenShift schema, creates whichever OpenShift resources are defined

For each required parameter, a default value is provided.  Optional parameters can be left blank.  And each parameter field has help text available via clicking the help icon located just right of the parameter input field.

For each of the build step or post build step plugins, the bearer authentication token can be provided by the user via the following:
	- the field for the token in the specific build step
	- if a string parameter with the key of `AUTH_TOKEN` is set in the Jenkins Job panel, where the value is the token
	- if a global property with the key of `AUTH_TOKEN` is set in the `Manage Jenkins` panel, where the value is the token

Otherwise, the plugin will assume you are running off of the OpenShift Jenkins Docker image (http://github.com/openshift/jenkins), and will read in the token from a well known location in the image that allows authorized access to the OpenShift master associated with the running OpenShift Jenkins image.

The CA cert is currently pulled from a well known location in the OpenShift Jenkins Docker image.

For "Monitor OpenShift ImageStreams", only specifying the token in the plugin configuration or leveraging the token embedded in the OpenShift Jenkins image is supported.
