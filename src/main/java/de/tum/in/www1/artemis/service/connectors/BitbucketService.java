package de.tum.in.www1.artemis.service.connectors;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import de.tum.in.www1.artemis.domain.ProgrammingExercise;
import de.tum.in.www1.artemis.domain.ProgrammingExerciseParticipation;
import de.tum.in.www1.artemis.domain.User;
import de.tum.in.www1.artemis.domain.VcsRepositoryUrl;
import de.tum.in.www1.artemis.exception.BitbucketException;
import de.tum.in.www1.artemis.service.UserService;
import de.tum.in.www1.artemis.web.rest.util.HeaderUtil;

@Service
@Profile("bitbucket")
public class BitbucketService implements VersionControlService {

    private final Logger log = LoggerFactory.getLogger(BitbucketService.class);

    @Value("${artemis.jira.admin-group-name}")
    private String ADMIN_GROUP_NAME;

    @Value("${artemis.version-control.url}")
    private URL BITBUCKET_SERVER_URL;

    @Value("${artemis.version-control.user}")
    private String BITBUCKET_USER;

    @Value("${artemis.version-control.secret}")
    private String BITBUCKET_PASSWORD;

    @Value("${artemis.lti.user-prefix-edx}")
    private String USER_PREFIX_EDX = "";

    @Value("${artemis.lti.user-prefix-u4i}")
    private String USER_PREFIX_U4I = "";

    private final UserService userService;

    private final RestTemplate restTemplate;

    public BitbucketService(UserService userService, RestTemplate restTemplate) {
        this.userService = userService;
        this.restTemplate = restTemplate;
    }

    @Override
    public void configureRepository(URL repositoryUrl, String username) {
        if (username.startsWith(USER_PREFIX_EDX) || username.startsWith((USER_PREFIX_U4I))) {
            // It is an automatically created user

            User user = userService.getUserByLogin(username).get();

            if (!userExists(username)) {
                log.debug("Bitbucket user {} does not exist yet", username);
                String displayName = (user.getFirstName() + " " + user.getLastName()).trim();
                createUser(username, userService.decryptPasswordByLogin(username).get(), user.getEmail(), displayName);

                try {
                    addUserToGroups(username, user.getGroups());
                }
                catch (BitbucketException e) {
                    /*
                     * This might throw exceptions, for example if the group does not exist on Bitbucket. We can safely ignore them.
                     */
                }
            }
            else {
                log.debug("Bitbucket user {} already exists", username);
            }

        }

        giveWritePermission(getProjectKeyFromUrl(repositoryUrl), getRepositorySlugFromUrl(repositoryUrl), username);
        protectBranches(getProjectKeyFromUrl(repositoryUrl), getRepositorySlugFromUrl(repositoryUrl));
    }

    /**
     * This methods protects the repository on the Bitbucket server by using a REST-call to setup branch protection.
     * The branch protection is applied to all branches and prevents rewriting the history (force-pushes) and deletion of branches.
     * @param projectKey The project key of the repository that should be protected
     * @param repositorySlug The slug of the repository that should be protected
     */
    private void protectBranches(String projectKey, String repositorySlug) {
        String baseUrl = BITBUCKET_SERVER_URL + "/rest/branch-permissions/2.0/projects/" + projectKey + "/repos/" + repositorySlug + "/restrictions";
        log.debug("Setting up branch protection for repository " + repositorySlug);

        // Payload according to https://docs.atlassian.com/bitbucket-server/rest/4.2.0/bitbucket-ref-restriction-rest.html
        HashMap<String, Object> matcher = new HashMap<>();

        // A wildcard (*) ist used to protect all branches
        matcher.put("displayId", "*");
        matcher.put("id", "*");
        HashMap<String, Object> type = new HashMap<>();
        type.put("id", "PATTERN");
        type.put("name", "Pattern");
        matcher.put("type", type);
        matcher.put("active", true);

        HashMap<String, Object> fastForwardOnlyType = new HashMap<>();
        fastForwardOnlyType.put("type", "fast-forward-only"); // Prevent force-pushes
        fastForwardOnlyType.put("matcher", matcher);

        HashMap<String, Object> noDeletesType = new HashMap<>();
        noDeletesType.put("type", "no-deletes"); // Prevent deletion of branches
        noDeletesType.put("matcher", matcher);

        List<Object> body = new ArrayList<>();
        body.add(fastForwardOnlyType);
        body.add(noDeletesType);

        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        headers.setContentType(new MediaType("application", "vnd.atl.bitbucket.bulk+json")); // Set content-type manually as required by Bitbucket
        HttpEntity<?> entity = new HttpEntity<>(body, headers);
        try {
            restTemplate.exchange(baseUrl, HttpMethod.POST, entity, Object.class);
        }
        catch (Exception emAll) {
            log.error("Exception occurred while protecting repository " + repositorySlug, emAll);
        }

        log.debug("Branch protection for repository " + repositorySlug + " set up");
    }

    @Override
    public void addWebHook(URL repositoryUrl, String notificationUrl, String webHookName) {
        if (!webHookExists(getProjectKeyFromUrl(repositoryUrl), getRepositorySlugFromUrl(repositoryUrl), notificationUrl)) {
            createWebHook(getProjectKeyFromUrl(repositoryUrl), getRepositorySlugFromUrl(repositoryUrl), notificationUrl, webHookName);
        }
    }

    @Override
    public void deleteProject(String projectKey) {
        String baseUrl = BITBUCKET_SERVER_URL + "/rest/api/1.0/projects/" + projectKey;
        log.info("Delete bitbucket project " + projectKey);
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        try {
            restTemplate.exchange(baseUrl, HttpMethod.DELETE, entity, Map.class);
        }
        catch (Exception e) {
            log.error("Could not delete project", e);
        }
    }

    @Override
    public void deleteRepository(URL repositoryUrl) {
        deleteRepositoryImpl(getProjectKeyFromUrl(repositoryUrl), getRepositorySlugFromUrl(repositoryUrl));
    }

    @Override
    public URL getRepositoryWebUrl(ProgrammingExerciseParticipation participation) {
        try {
            return new URL(BITBUCKET_SERVER_URL + "/projects/" + getProjectKeyFromUrl(participation.getRepositoryUrlAsUrl()) + "/repos/"
                    + getRepositorySlugFromUrl(participation.getRepositoryUrlAsUrl()) + "/browse");
        }
        catch (MalformedURLException e) {
            log.error("Couldn't construct repository web URL");
        }
        return BITBUCKET_SERVER_URL;
    }

    @Override
    public VcsRepositoryUrl getCloneRepositoryUrl(String projectKey, String repositorySlug) {
        final var cloneUrl = new BitbucketRepositoryUrl(projectKey, repositorySlug);
        log.debug("getCloneURL: " + cloneUrl.toString());

        return cloneUrl;
    }

    @Override
    public VcsRepositoryUrl copyRepository(String sourceProjectKey, String sourceRepositoryName, String targetProjectKey, String targetRepositoryName) {
        sourceRepositoryName = sourceRepositoryName.toLowerCase();
        targetRepositoryName = targetRepositoryName.toLowerCase();
        final var targetRepoSlug = targetProjectKey.toLowerCase() + "-" + targetRepositoryName;
        final Map<String, Object> body = new HashMap<>();
        body.put("name", targetRepoSlug);
        final var projectMap = new HashMap<>();
        projectMap.put("key", targetProjectKey);
        body.put("project", projectMap);
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(body, headers);

        log.info("Try to copy repository " + sourceProjectKey + "/repos/" + sourceRepositoryName + " into " + targetRepoSlug);
        final String repoUrl = BITBUCKET_SERVER_URL + "/rest/api/1.0/projects/" + sourceProjectKey + "/repos/" + sourceRepositoryName;
        try {
            final var response = restTemplate.postForEntity(new URI(repoUrl), entity, Map.class);
            if (response.getStatusCode().equals(HttpStatus.CREATED)) {
                return new BitbucketRepositoryUrl(targetProjectKey, targetRepoSlug);
            }
        }
        catch (URISyntaxException e) {
            throw new BitbucketException("Invalid repository URL built while trying to fork: " + repoUrl);
        }
        catch (HttpClientErrorException e) {
            if (e.getStatusCode().equals(HttpStatus.CONFLICT)) {
                log.info("Repository already exists. Going to recover repository information...");
                return new BitbucketRepositoryUrl(sourceProjectKey, sourceRepositoryName);
            }
            else {
                log.error("Could not fork base repository " + sourceProjectKey + "/repos/" + sourceRepositoryName + " into " + targetRepoSlug, e);
                throw new BitbucketException("Error while forking repository", e);
            }
        }
        catch (Exception emAll) {
            log.error("Could not fork base repository " + sourceProjectKey + "/repos/" + sourceRepositoryName + " into " + targetRepoSlug, emAll);
            throw new BitbucketException("Error while forking repository", emAll);
        }

        return null;
    }

    /**
     * Gets the project key from the given URL
     *
     * @param repositoryUrl The complete repository-url (including protocol, host and the complete path)
     * @return The project key
     * @throws BitbucketException if the URL is invalid and no project key could be extracted
     */
    private String getProjectKeyFromUrl(URL repositoryUrl) throws BitbucketException {
        // https://ga42xab@repobruegge.in.tum.de/scm/EIST2016RME/RMEXERCISE-ga42xab.git
        String[] urlParts = repositoryUrl.getFile().split("/");
        if (urlParts.length > 2) {
            return urlParts[2];
        }

        log.error("No project key could be found for repository {}", repositoryUrl);
        throw new BitbucketException("No project key could be found");
    }

    /**
     * Gets the repository slug from the given URL
     *
     * @param repositoryUrl The complete repository-url (including protocol, host and the complete path)
     * @return The repository slug
     * @throws BitbucketException if the URL is invalid and no repository slug could be extracted
     */
    public String getRepositorySlugFromUrl(URL repositoryUrl) throws BitbucketException {
        // https://ga42xab@repobruegge.in.tum.de/scm/EIST2016RME/RMEXERCISE-ga42xab.git
        String[] urlParts = repositoryUrl.getFile().split("/");
        if (urlParts.length > 3) {
            String repositorySlug = urlParts[3];
            if (repositorySlug.endsWith(".git")) {
                repositorySlug = repositorySlug.substring(0, repositorySlug.length() - 4);
            }
            return repositorySlug;
        }

        log.error("No repository slug could be found for repository {}", repositoryUrl);
        throw new BitbucketException("No repository slug could be found");
    }

    /**
     * Checks if an username exists on Bitbucket
     *
     * @param username the Bitbucket username to check
     * @return true if it exists
     * @throws BitbucketException
     */
    private Boolean userExists(String username) throws BitbucketException {
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        try {
            restTemplate.exchange(BITBUCKET_SERVER_URL + "/rest/api/1.0/users/" + username, HttpMethod.GET, entity, Map.class);
        }
        catch (HttpClientErrorException e) {
            if (e.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                return false;
            }
            log.error("Could not check if user  " + username + " exists.", e);
            throw new BitbucketException("Could not check if user exists");
        }
        return true;
    }

    /**
     * Creates an user on Bitbucket
     *
     * @param username     The wanted Bitbucket username
     * @param password     The wanted passowrd in clear text
     * @param emailAddress The eMail address for the user
     * @param displayName  The display name (full name)
     * @throws BitbucketException if the rest request to Bitbucket for creating the user failed.
     */
    public void createUser(String username, String password, String emailAddress, String displayName) throws BitbucketException {
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(BITBUCKET_SERVER_URL + "/rest/api/1.0/admin/users").queryParam("name", username)
                .queryParam("email", emailAddress).queryParam("emailAddress", emailAddress).queryParam("password", password).queryParam("displayName", displayName)
                .queryParam("addToDefaultGroup", "true").queryParam("notify", "false");

        HttpEntity<?> entity = new HttpEntity<>(headers);

        log.debug("Creating Bitbucket user {} ({})", username, emailAddress);

        try {
            restTemplate.exchange(builder.build().encode().toUri(), HttpMethod.POST, entity, Map.class);
        }
        catch (HttpClientErrorException e) {
            log.error("Could not create Bitbucket user " + username, e);
            throw new BitbucketException("Error while creating user");
        }
    }

    /**
     * Adds an Bitbucket user to (multiple) Bitbucket groups
     *
     * @param username The Bitbucket username
     * @param groups   Names of Bitbucket groups
     * @throws BitbucketException if the rest request to Bitbucket for adding the user to the specified groups failed.
     */
    public void addUserToGroups(String username, Set<String> groups) throws BitbucketException {
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);

        Map<String, Object> body = new HashMap<>();
        body.put("user", username);
        body.put("groups", groups);
        HttpEntity<?> entity = new HttpEntity<>(body, headers);

        log.debug("Adding Bitbucket user {} to groups {}", username, groups);

        try {
            restTemplate.exchange(BITBUCKET_SERVER_URL + "/rest/api/1.0/admin/users/add-groups", HttpMethod.POST, entity, Map.class);
        }
        catch (HttpClientErrorException e) {
            log.error("Could not add Bitbucket user " + username + " to groups" + groups, e);
            throw new BitbucketException("Error while adding Bitbucket user to groups");
        }
    }

    /**
     * Gives user write permissions for a repository.
     *
     * @param projectKey     The project key of the repository's project.
     * @param repositorySlug The repository's slug.
     * @param username       The user whom to give write permissions.
     */
    // TODO: Refactor to also use setStudentRepositoryPermission.
    private void giveWritePermission(String projectKey, String repositorySlug, String username) throws BitbucketException {
        String baseUrl = BITBUCKET_SERVER_URL + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repositorySlug + "/permissions/users?name=";// NAME&PERMISSION
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        try {
            restTemplate.exchange(baseUrl + username + "&permission=REPO_WRITE", HttpMethod.PUT, entity, Map.class);
        }
        catch (Exception e) {
            log.error("Could not give write permission", e);
            throw new BitbucketException("Error while giving repository permissions");
        }
    }

    @Override
    public void setRepositoryPermissionsToReadOnly(URL repositoryUrl, String projectKey, String username) throws BitbucketException {
        setStudentRepositoryPermission(repositoryUrl, projectKey, username, VersionControlRepositoryPermission.READ_ONLY);
    }

    private void setStudentRepositoryPermission(URL repositoryUrl, String projectKey, String username, VersionControlRepositoryPermission repositoryPermission)
            throws BitbucketException {
        String permissionString = repositoryPermission == VersionControlRepositoryPermission.READ_ONLY ? "READ" : "WRITE";
        String repositorySlug = getRepositoryName(repositoryUrl);
        String baseUrl = BITBUCKET_SERVER_URL + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repositorySlug + "/permissions/users?name=";// NAME&PERMISSION
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        try {
            restTemplate.exchange(baseUrl + username + "&permission=REPO_" + permissionString, HttpMethod.PUT, entity, Map.class);
        }
        catch (Exception e) {
            log.error("Could not give " + repositoryPermission + " permissions", e);
            throw new BitbucketException("Error while giving repository permissions");
        }
    }

    @Override
    public String checkIfProjectExists(String projectKey, String projectName) {
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        ResponseEntity<Map> response = null;
        try {
            // first check that the project key is unique
            response = restTemplate.exchange(BITBUCKET_SERVER_URL + "/rest/api/1.0/projects/" + projectKey, HttpMethod.GET, entity, Map.class);
            log.warn("Bitbucket project with key " + projectKey + " already exists");
            return "The project " + projectKey + " already exists in the VCS Server. Please choose a different short name!";
        }
        catch (HttpClientErrorException e) {
            log.debug("Bitbucket project " + projectKey + " does not exit");
            if (e.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                // only if this is the case, we additionally check that the project name is unique

                response = restTemplate.exchange(BITBUCKET_SERVER_URL + "/rest/api/1.0/projects?name=" + projectName, HttpMethod.GET, entity, Map.class);

                if ((Integer) response.getBody().get("size") != 0) {
                    List<Object> vcsProjects = (List<Object>) response.getBody().get("values");
                    for (Object vcsProject : vcsProjects) {
                        String vcsProjectName = (String) ((Map) vcsProject).get("name");
                        if (vcsProjectName.equalsIgnoreCase(projectName)) {
                            log.warn("Bitbucket project with name" + projectName + " already exists");
                            return "The project " + projectName + " already exists in the VCS Server. Please choose a different title!";
                        }
                    }
                }
                return null;
            }
        }
        return "The project already exists in the VCS Server. Please choose a different title and short name!";
    }

    /**
     * Create a new project
     *
     * @param programmingExercise
     * @throws BitbucketException if the project could not be created
     */
    @Override
    public void createProjectForExercise(ProgrammingExercise programmingExercise) throws BitbucketException {
        String projectKey = programmingExercise.getProjectKey();
        String projectName = programmingExercise.getProjectName();
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);

        Map<String, Object> body = new HashMap<>();
        body.put("key", projectKey);
        body.put("name", projectName);
        // TODO: add a description
        HttpEntity<?> entity = new HttpEntity<>(body, headers);

        log.debug("Creating Bitbucket project {} with key {}", projectName, projectKey);

        try {
            restTemplate.exchange(BITBUCKET_SERVER_URL + "/rest/api/1.0/projects", HttpMethod.POST, entity, Map.class);
            grantGroupPermissionToProject(projectKey, ADMIN_GROUP_NAME, "PROJECT_ADMIN"); // admins get administrative permissions

            if (programmingExercise.getCourse().getInstructorGroupName() != null && !programmingExercise.getCourse().getInstructorGroupName().isEmpty()) {
                grantGroupPermissionToProject(projectKey, programmingExercise.getCourse().getInstructorGroupName(), "PROJECT_ADMIN"); // instructors get administrative permissions
            }

            if (programmingExercise.getCourse().getTeachingAssistantGroupName() != null && !programmingExercise.getCourse().getTeachingAssistantGroupName().isEmpty()) {
                grantGroupPermissionToProject(projectKey, programmingExercise.getCourse().getTeachingAssistantGroupName(), "PROJECT_WRITE"); // teachingAssistants get
                                                                                                                                             // write-permissions
            }
        }
        catch (HttpClientErrorException e) {
            log.error("Could not create Bitbucket project {} with key {}", projectName, projectKey, e);
            throw new BitbucketException("Error while creating Bitbucket project. Try a different name!");
        }
    }

    /**
     * Create a new repo
     *
     * @param repoName   The project name
     * @param projectKey The project key of the parent project
     * @throws BitbucketException if the repo could not be created
     */
    private void createRepository(String projectKey, String repoName) throws BitbucketException {
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);

        Map<String, Object> body = new HashMap<>();
        body.put("name", repoName.toLowerCase());
        HttpEntity<?> entity = new HttpEntity<>(body, headers);

        log.debug("Creating Bitbucket repo {} with parent key {}", repoName, projectKey);

        try {
            restTemplate.exchange(BITBUCKET_SERVER_URL + "/rest/api/1.0/projects/" + projectKey + "/repos", HttpMethod.POST, entity, Map.class);
        }
        catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                log.info("Repository {} (parent {}) already exists, reusing it...", repoName, projectKey);
                return;
            }
            log.error("Could not create Bitbucket repo {} with projectKey key {}", repoName, projectKey, e);
            throw new BitbucketException("Error while creating Bitbucket repo");
        }
    }

    private void grantGroupPermissionToProject(String projectKey, String groupName, String permission) {
        String baseUrl = BITBUCKET_SERVER_URL + "/rest/api/1.0/projects/" + projectKey + "/permissions/groups/?name="; // GROUPNAME&PERMISSION
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        try {
            restTemplate.exchange(baseUrl + groupName + "&permission=" + permission, HttpMethod.PUT, entity, Map.class);
        }
        catch (Exception e) {
            log.error("Could not give project permission", e);
            throw new BitbucketException("Error while giving project permissions");
        }
    }

    /**
     * Get all existing WebHooks for a specific repository.
     *
     * @param projectKey     The project key of the repository's project.
     * @param repositorySlug The repository's slug.
     * @return A map of all ids of the WebHooks to the URL they notify.
     * @throws BitbucketException if the request to get the WebHooks failed
     */
    private Map<Integer, String> getExistingWebHooks(String projectKey, String repositorySlug) throws BitbucketException {
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        String baseUrl = BITBUCKET_SERVER_URL + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repositorySlug + "/webhooks";

        HttpEntity<?> entity = new HttpEntity<>(headers);
        ResponseEntity<Map> response;
        try {
            response = restTemplate.exchange(baseUrl, HttpMethod.GET, entity, Map.class);
        }
        catch (Exception e) {
            log.error("Error while getting existing WebHooks", e);
            throw new BitbucketException("Error while getting existing WebHooks", e);
        }

        Map<Integer, String> webHooks = new HashMap<>();

        if (response != null && response.getStatusCode().equals(HttpStatus.OK)) {
            // TODO: BitBucket uses a pagination API to split up the responses, so we might have to check all pages
            List<Map<String, Object>> rawWebHooks = (List<Map<String, Object>>) response.getBody().get("values");
            for (Map<String, Object> rawWebHook : rawWebHooks) {
                webHooks.put((Integer) rawWebHook.get("id"), (String) rawWebHook.get("url"));
            }
            return webHooks;
        }
        log.error("Error while getting existing WebHooks for {}-{}: Invalid response", projectKey, repositorySlug);
        throw new BitbucketException("Error while getting existing WebHooks: Invalid response");
    }

    private boolean webHookExists(String projectKey, String repositorySlug, String notificationUrl) {
        Map<Integer, String> webHooks = getExistingWebHooks(projectKey, repositorySlug);
        return webHooks.values().contains(notificationUrl);
    }

    private void createWebHook(String projectKey, String repositorySlug, String notificationUrl, String webHookName) {
        log.debug("Creating WebHook for Repository {}-{} ({})", projectKey, repositorySlug, notificationUrl);
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        String baseUrl = BITBUCKET_SERVER_URL + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repositorySlug + "/webhooks";

        Map<String, Object> body = new HashMap<>();
        body.put("name", webHookName);
        body.put("url", notificationUrl);
        body.put("events", new ArrayList<>());
        ((List) body.get("events")).add("repo:refs_changed"); // Inform on push
        // TODO: We might want to add a token to ensure the notification is valid

        HttpEntity<?> entity = new HttpEntity<>(body, headers);

        try {
            restTemplate.exchange(baseUrl, HttpMethod.POST, entity, Map.class);
        }
        catch (HttpClientErrorException e) {
            log.error("Could not add create WebHook for {}-{} ({})", projectKey, repositorySlug, notificationUrl, e);
            throw new BitbucketException("Error while creating WebHook");
        }
    }

    private void deleteWebHook(String projectKey, String repositorySlug, Integer webHookId) {
        String baseUrl = BITBUCKET_SERVER_URL + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repositorySlug + "/webhooks/" + webHookId;
        log.info("Delete WebHook {} on project {}-{}", webHookId, projectKey, repositorySlug);
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        try {
            restTemplate.exchange(baseUrl, HttpMethod.DELETE, entity, Map.class);
        }
        catch (Exception e) {
            log.error("Could not delete WebHook", e);
        }
    }

    private void deleteExistingWebHooks(String projectKey, String repositorySlug) {
        Map<Integer, String> webHooks = getExistingWebHooks(projectKey, repositorySlug);
        for (Integer webHookId : webHooks.keySet()) {
            deleteWebHook(projectKey, repositorySlug, webHookId);
        }
    }

    /**
     * Deletes the given repository from Bitbucket.
     *
     * @param projectKey     The project key of the repository's project.
     * @param repositorySlug The repository's slug.
     */
    private void deleteRepositoryImpl(String projectKey, String repositorySlug) {
        String baseUrl = BITBUCKET_SERVER_URL + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repositorySlug;
        log.info("Delete repository " + baseUrl);
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        try {
            restTemplate.exchange(baseUrl, HttpMethod.DELETE, entity, Map.class);
        }
        catch (Exception e) {
            log.error("Could not delete repository", e);
        }
    }

    /**
     * Check if the given repository url is valid and accessible on Bitbucket.
     * 
     * @param repositoryUrl
     * @return
     */
    @Override
    public Boolean repositoryUrlIsValid(URL repositoryUrl) {
        String projectKey;
        String repositorySlug;
        try {
            projectKey = getProjectKeyFromUrl(repositoryUrl);
            repositorySlug = getRepositorySlugFromUrl(repositoryUrl);
        }
        catch (BitbucketException e) {
            // Either the project Key or the repository slug could not be extracted, therefor this can't be a valid URL
            return false;
        }

        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        try {
            restTemplate.exchange(BITBUCKET_SERVER_URL + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repositorySlug, HttpMethod.GET, entity, Map.class);
        }
        catch (Exception e) {
            return false;
        }
        return true;
    }

    @Override
    public String getLastCommitHash(Object requestBody) throws BitbucketException {

        // NOTE the requestBody should look like this:
        // {"eventKey":"...","date":"...","actor":{...},"repository":{...},"changes":[{"ref":{...},"refId":"refs/heads/master","fromHash":"5626436a443eb898a5c5f74b6352f26ea2b7c84e","toHash":"662868d5e16406d1dd4dcfa8ac6c46ee3d677924","type":"UPDATE"}]}
        // we are interested in the toHash
        try {
            Map<String, Object> requestBodyMap = (Map<String, Object>) requestBody;
            List<Object> changes = (List<Object>) requestBodyMap.get("changes");
            Map<String, Object> lastChange = (Map<String, Object>) changes.get(0);
            String hash = (String) lastChange.get("toHash");
            return hash;
        }
        catch (Exception e) {
            log.error("Error when getting hash of last commit");
            throw new BitbucketException("Could not get hash of last commit", e);
        }
    }

    @Override
    public void createRepository(String entityName, String topLevelEntity, String parentEntity) {
        createRepository(entityName, topLevelEntity);
    }

    @Override
    public String getRepositoryName(URL repositoryUrl) {
        return getRepositorySlugFromUrl(repositoryUrl);
    }

    public final class BitbucketRepositoryUrl extends VcsRepositoryUrl {

        public BitbucketRepositoryUrl(String projectKey, String repositorySlug) {
            super();
            final var urlString = BITBUCKET_SERVER_URL.getProtocol() + "://" + BITBUCKET_SERVER_URL.getAuthority() + buildRepositoryPath(projectKey, repositorySlug);
            try {
                this.url = new URL(urlString);
            }
            catch (MalformedURLException e) {
                throw new BitbucketException("Could not build clone URL", e);
            }
        }

        private BitbucketRepositoryUrl(String urlString) {
            try {
                this.url = new URL(urlString);
            }
            catch (MalformedURLException e) {
                throw new BitbucketException("Could not build clone URL", e);
            }
        }

        @Override
        public VcsRepositoryUrl withUser(String username) {
            this.username = username;
            return new BitbucketRepositoryUrl(url.toString().replaceAll("(https?://)(.*)", "$1" + username + "@$2"));
        }

        private String buildRepositoryPath(String projectKey, String repositorySlug) {
            return BITBUCKET_SERVER_URL.getPath() + "/scm/" + projectKey + "/" + repositorySlug + ".git";
        }
    }
}
