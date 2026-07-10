<#import "../layout.ftl" as layout>
<@layout.email>
  <h2>${app_name} - ${messages['email.template.route_step_rejected.subject']}</h2>
  <p>${messages('email.template.route_step_rejected.hello', user_name)}</p>
  <#-- User-authored values (step name, comment, document title) are HTML-escaped with ?html:
       EmailUtil's FreeMarker config has no auto-escaping, so a raw interpolation would inject
       user HTML into the email. base_url/document_id are system values and stay raw. -->
  <p>${messages('email.template.route_step_rejected.instruction1', step_name?html)}</p>
  <#if step_comment??>
  <p>${messages('email.template.route_step_rejected.comment', step_comment?html)}</p>
  </#if>
  <p>${messages['email.template.route_step_rejected.instruction2']}</p>
  <a href="${base_url}/#/document/view/${document_id}">${document_title?html}</a>
</@layout.email>
