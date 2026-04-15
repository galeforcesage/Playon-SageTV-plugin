package sage;

public interface SageTVPlugin extends SageTVEventListener {
    void start();
    void stop();
    void destroy();

    String[] getConfigSettings();
    String getConfigValue(String setting);
    String[] getConfigValues(String setting);

    int CONFIG_BOOL = 1;
    int CONFIG_INTEGER = 2;
    int CONFIG_TEXT = 3;
    int CONFIG_CHOICE = 4;
    int CONFIG_MULTICHOICE = 5;
    int CONFIG_FILE = 6;
    int CONFIG_DIRECTORY = 7;
    int CONFIG_BUTTON = 8;
    int CONFIG_PASSWORD = 9;

    int getConfigType(String setting);
    void setConfigValue(String setting, String value);
    void setConfigValues(String setting, String[] values);
    String[] getConfigOptions(String setting);
    String getConfigHelpText(String setting);
    String getConfigLabel(String setting);
    void resetConfig();
}
