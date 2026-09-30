<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>FreeMarker</title>
</head>
<body>

<h1>Hello ${name}!</h1>

<#if users??>
    <ul>
        <#list users as user>
            <li>${user.name} - ${user.age} - ${user.sss}</li>
        </#list>
    </ul>
</#if>

</body>
</html>