<#import "../layout.ftl" as layout>
<@layout.email>
  <h2>${app_name} - ${messages['email.template.route_step_validate.subject']}</h2>
  <#-- user_name is the recipient's username. Under LDAP provisioning it is the login-supplied,
       unvalidated username, so it is user-controlled and must be HTML-escaped (?html) — EmailUtil's
       FreeMarker config has no auto-escaping. The {0} message substitution places the escaped string
       as a literal, so escaping the argument is correct. -->
  <p>${messages('email.template.route_step_validate.hello', user_name?html)}</p>
  <p>${messages['email.template.route_step_validate.instruction1']}</p>
  <p>${messages['email.template.route_step_validate.instruction2']}</p>
  <#-- The document title is user-authored: HTML-escape it with ?html. EmailUtil's FreeMarker config
       has no auto-escaping, so a raw interpolation would inject user HTML into the email (mirrors the
       route_step_rejected template). base_url/document_id are system values and stay raw. -->
  <a href="${base_url}/#/document/view/${document_id}">${document_title?html}</a>
</@layout.email>
