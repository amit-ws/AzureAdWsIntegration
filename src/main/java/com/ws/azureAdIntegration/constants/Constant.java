package com.ws.azureAdIntegration.constants;

public class Constant {
    public static final String ADD = "Add";
    public static final String UPDATE = "Update";
    public static final String AZURE_AD_DATA_SYNC_START = "Azure-AD data sync started";
    public static final String AZURE_RESOURCE_DATA_SYNC_START = "Azure resources related data sync started";
    public static final String AZURE_RESOURCE_DATA_TRUNCATED = "Azure resources related data successfully truncated";
    public static final String AZURE_AD_DATA_SYNC_END = "Azure-AD data sync ended successfully";
    public static final String AZURE_RESOURCE_DATA_SYNC_END = "Azure resources related data sync ended successfully";
    public static final String AZURE_RESOURCE_DATA_SYNC_SKIPPED = "Skipped Azure-Resources data sync as no subscription-id was found";
    public static final String AZURE_DATA_ASYNCHRONOUS_FAILURE = "Skipped Azure data ASYNCHRONOUS failure with message: %s";
    public static final String AZURE_SYNC_FAILURE = "Azure data sync failure. Error: %s";
    public static final String AZURE_SYNC_TIME_OUT = "Azure data sync timeout";
    public static final String AZURE_CREDENTIALS_SAVED = "User azure credentials saved";
    public static final  String AZURE_TENANT_SAVED = "Azure tenant (Org) saved";
    public static final String AZURE_APPLICATION__SAVED = "Azure application(s) saved";
    public static final String AZURE_USERS_SAVED = "Azure users saved";
    public static final String AZURE_GROUP_SAVED = "Azure groups saved";
    public static final String AZURE_DEVICE_SAVED = "Azure devices saved";
    public static final String AZURE_USERS_GROUPS_MAPPED = "Azure users mapped with respective azure groups";
    public static final String AZURE_USERS_DEVICES_MAPPED = "Azure users mapped with respective azure devices";


    /* Secret related */
    public static final String AZURE_CLIENT_SECRET = "Azure client secret";
    public static final String GCP_PRIVATE_KEY = "GCP private key";


    /*Azure resource related*/
    public static final  String AZURE_VMS_SYNCED = "Azure VMs synced";
    public static final String AZURE_SUBSCRIPTION_SYNCED = "Azure subscription(s) synced";
    public static final String AZURE_RESOURCE_GROUPS_SYNCED = "Azure resource groups synced";
    public static final String AZURE_STORAGES_SYNCED = "Azure storages related data synced";
    public static final String AZURE_SERVER_DATABASES_SYNCED = "Azure Servers and associated DBs related data synced";
    public static final String AZURE_SERVER_ROLE_DEFINITION_SYNCED = "Azure RoleDefinitions and associated permissions, actions/notActions data synced";
    public static final String AZURE_SERVER_ROLE_ASSIGNMENT_SYNCED = "Azure Role assignment data synced";

    /*Azure resource error related*/
    public static final String ERROR_IN_SYNCING_AZURE_RESOURCES = "Error in syncing azure ";


    public static final String AZURE_SUBSCRIPTION_ID_UPDATED = "Subscription ID updated for Azure user credential";


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


    // Azure resource scopes
    public static final String SUBSCRIPTION_LEVEL_SCOPE = "/subscriptions/%s";
    public static final String RESOURCE_GROUP_LEVEL_SCOPE = "/subscriptions/%s/resourceGroups/%s";
    public static final String VM_LEVEL_SCOPE = "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Compute/virtualMachines/%s";
    public static final String STORAGE_ACCOUNT_LEVEL_SCOPE = "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Storage/storageAccounts/%s";
    public static final String DATABASE_LEVEL_SCOPE = "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Sql/servers/%s/databases/%s";


    //Azure Scheduler
    public static final String TOTAL_FOUND_ROLE_ASSIGNMENTS_TO_BE_REMOVED = "Total %s AzureRoleAssignments found to be removed";

    // AKS and K8 resources
    public static final String AZURE_KUBERNETES_CLUSTERS_SYNCED = "Azure kubernetes clusters data synced";
    public static final String INSUFFICIENT_PRIVILEGEE_FOR_AKS_ADMIN_CONFIG_FETCH = "InSufficient Privilege to fetch AKS cluster admin credential configs";
    public static final String KUBERNETES_RESOURCES_SYNCED = "Azure kubernetes resources data synced";
    public static final String ERROR_IN_SYNCING_KUBERNETES_DATA = "Error in syncing kubernetes resources data ";
    public static final String KUBERNETES_RESOURCES_DATA_SYNC_SKIPPED = "Skipped Kubernetes-Resources data sync as no Azure Kubernetes (AKS) was found";


}
