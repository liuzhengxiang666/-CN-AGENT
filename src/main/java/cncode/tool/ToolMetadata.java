package cncode.tool;

public record ToolMetadata(String name, String description, String parametersJsonSchema) {
    public ToolMetadata {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("工具描述不能为空");
        }
        if (parametersJsonSchema == null || parametersJsonSchema.isBlank()) {
            throw new IllegalArgumentException("工具参数 Schema 不能为空");
        }
    }

    public String toOpenAiToolJson() {
        return "{"
                + "\"type\":\"function\","
                + "\"function\":{"
                + "\"name\":" + ToolJson.quote(name) + ","
                + "\"description\":" + ToolJson.quote(description) + ","
                + "\"parameters\":" + parametersJsonSchema
                + "}"
                + "}";
    }
}
