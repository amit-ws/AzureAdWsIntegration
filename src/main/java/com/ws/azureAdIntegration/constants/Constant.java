package com.ws.azureAdIntegration.constants;

public interface Constant {
    String ADD = "Add";
    public static final String AZURE_AD_DATA_SYNC_START = "Azure-AD data sync started";
    public static final String AZURE_RESOURCE_DATA_SYNC_START = "Azure resources related data sync started";
    public static final String AZURE_RESOURCE_DATA_TRUNCATED = "Azure resources related data successfully truncated";
    public static final String AZURE_AD_DATA_SYNC_END = "Azure-AD data sync ended successfully";
    public static final String AZURE_RESOURCE_DATA_SYNC_END = "Azure resources related data sync ended successfully";
    String AZURE_SYNC_FAILURE = "Azure data sync failure";
    String AZURE_CREDENTIALS_SAVED = "User azure credentials saved";
    String AZURE_TENANT_SAVED = "Azure tenant (Org) saved";
    String AZURE_APPLICATION__SAVED = "Azure application(s) saved";
    String AZURE_USERS_SAVED = "Azure users saved";
    String AZURE_GROUP_SAVED = "Azure groups saved";
    String AZURE_DEVICE_SAVED = "Azure devices saved";
    String AZURE_USERS_GROUPS_MAPPED = "Azure users mapped with respective azure groups";
    String AZURE_USERS_DEVICES_MAPPED = "Azure users mapped with respective azure devices";


    /* Secret related */
    String AZURE_CLIENT_SECRET = "Azure client secret";
    String GCP_PRIVATE_KEY = "GCP private key";


    /*Azure resource related*/
    String AZURE_VMS_SYNCED = "Azure VMs synced";
    String AZURE_SUBSCRIPTION_SYNCED = "Azure subscription(s) synced";
    String AZURE_RESOURCE_GROUPS_SYNCED = "Azure resource groups synced";
    String AZURE_STORAGES_SYNCED = "Azure storages related data synced";
    String AZURE_SERVER_DATABASES_SYNCED = "Azure Servers and associated DBs related data synced";
    String AZURE_SERVER_ROLE_DEFINITION_SYNCED = "Azure RoleDefinitions and associated permissions, actions/notActions data synced";

    /*Azure resource error related*/
    String ERROR_IN_SYNCING_AZURE_RESOURCES = "Error in syncing azure ";


    String AZURE_SUBSCRIPTION_ID_UPDATED = "Subscription ID updated for Azure user credential";


    // Secret Encryption related
    public static final String ENCRYPTION_KEY = "12345678901234567890123456789012";
    public static final String ENCRYPTION_STANDARD = "AES";

    // Azure AD constants
    public static final String AZURE_RESPONSE_TYPE = "code";
    public static final String AZURE_RESPONSE_MODE = "query";

    // OAuth 2.0
    public static final String OAUTH = "oauth2";
    public static final String OAUTH_VERSION = "v2.0";
    public static final String OAUTH_TYPE = "authorize";

    // SSO keys
    public static final String CLIENT_ID_PARAM = "client_id";
    public static final String RESPONSE_TYPE_PARAM = "response_type";
    public static final String REDIRECT_URI_PARAM = "redirect_uri";
    public static final String RESPONSE_MODE_PARAM = "response_mode";
    public static final String SCOPE_PARAM = "scope";

}
