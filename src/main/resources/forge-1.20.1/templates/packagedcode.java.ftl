<#-- @formatter:off -->
<#-- Defaults matter here: elements saved before these fields existed deserialize to null, not to the
     field initialisers, because Gson builds the object without running them. -->
<#assign kind = data.classKind!"class">
<#assign superClass = (data.superClass!"")?trim>
<#assign interfaces = (data.interfaces!"")?trim>
<#assign components = (data.recordComponents!"")?trim>
package ${package}<#if data.targetPackage?has_content>.${data.targetPackage}</#if>;

<#if kind == "record">
public record ${name}(${components})<#if interfaces?has_content> implements ${interfaces}</#if> {
<#elseif kind == "enum">
public enum ${name}<#if interfaces?has_content> implements ${interfaces}</#if> {
<#elseif kind == "interface">
public interface ${name}<#if superClass?has_content> extends ${superClass}</#if> {
<#elseif kind == "@interface">
public @interface ${name} {
<#else>
public ${kind} ${name}<#if superClass?has_content> extends ${superClass}</#if><#if interfaces?has_content> implements ${interfaces}</#if> {
</#if>
}
<#-- @formatter:on -->
