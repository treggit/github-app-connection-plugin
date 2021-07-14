<%@ taglib prefix="props" tagdir="/WEB-INF/tags/props" %>
<%@ taglib prefix="bs" tagdir="/WEB-INF/tags" %>
<%@ taglib prefix="forms" uri="http://www.springframework.org/tags/form" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ include file="/include-internal.jsp"%>

<c:url value="/app/github/application/connection" var="action"/>

<div class="section noMargin">
    <h2 class="noBorder">GitHub App Connections</h2>
    <bs:smallNote>
        The list of connections to GitHub App.
    </bs:smallNote>

    <bs:messages key="appConnected"/>
    <bs:messages key="connectionDeleted"/>

    <c:set var="cameFromUrl" value="${param['cameFromUrl']}"/>
    <bs:refreshable containerId="connections" pageUrl="${pageUrl}">

        <forms:addButton id="addNewConnection" onclick="BS.AppGenerationDialog.createConnection(); return false">Add a new connection</forms:addButton>

        <c:if test="${not empty connections}">
            <l:tableWithHighlighting className="parametersTable" highlightImmediately="true">
                <tr>
                    <th style="width: 30%">App ID</th>
                    <th colspan="2">Description</th>
                </tr>
                <c:forEach var="connection" items="${connections}">
                    <c:if test="${connection.reserved == false}">
                        <tr>
                            <td class="highlight">
                                <c:out value="${connection.appId}"/>
                            </td>
                            <td class="highlight beforeActions">
                                <c:out value="${connection.description}"/>
                            </td>
                            <td class="edit highlight">
                                <a href="#" onclick="BS.ConnectionsDialog.deleteConnection('${connection.appId}')">Delete</a>
                            </td>
                        </tr>
                    </c:if>
                </c:forEach>
            </l:tableWithHighlighting>
        </c:if>
    </bs:refreshable>
</div>

<bs:modalDialog
        formId="generateAppConnectionForm"
        title="Generate an app"
        action="${action}"
        closeCommand="BS.AppGenerationDialog.close()"
        saveCommand="BS.AppGenerationDialog.submit()"
>
    <table class="runnerFormTable">
        <tr>
            <th>
                <label for="ownerUrl">Owner URL:</label><l:star/>
            </th>
            <td>
                <forms:textField id="ownerUrl" name="ownerUrl" className="longField"/><br>
                <forms:checkbox id="isOrganizationUrl" name="isOrganizationUrl" value="true" checked="false"/>
                <label for="isOrganizationUrl">Organization URL</label>
                <span class="error" id="error_targetUrl"></span>
            </td>
        </tr>
    </table>
    <div class="popupSaveButtonsBlock">
        <forms:submit id="generateAppConnectionDialogSubmit" label="Generate an app"/>
        <forms:cancel onclick="BS.AppGenerationDialog.close()"/>
<%--        <forms:saving id="generatingLinkProgress" className="progressRingInline"/>--%>
    </div>
    <input type="hidden" name="action" value="generateLink">

</bs:modalDialog>

<bs:dialog dialogId="connectionDialog"
           dialogClass="connectionDialog uploadDialog"
           title="Create a new connection"
           closeCommand="BS.ConnectionsDialog.close()">
    <forms:multipartForm id="connectionForm" action="${action}" targetIframe="hidden-iframe" onsubmit="return BS.ConnectionsDialog.validate();">
        <table class="runnerFormTable">
            <tr class="githubNote">
                <td colspan="2">
                    <div class="attentionComment">
                        Please enter a link to an account or an organisation, where GitHub App should be installed, and press <strong>"Install an app"</strong>. <br>
                        You will be redirected to the generated app, authorise it and install to the target repositories.
                        Afterwards enter the <strong>app id</strong> and upload the <strong>private key</strong> via the form below.
                    </div>
                </td>
            </tr>
            <tr>
                <th>
                    <label for="ownerUrl">Target URL:</label><l:star/><br>
                </th>
                <td>
                    <forms:textField id="ownerUrl" name="ownerUrl" className="longField"/><br>

                    <span class="error" id="error_targetUrl"></span>
                </td>
            </tr>
            <tr>
                <th>
                    <label for="appId">App ID:</label><l:star/>
                </th>
                <td>
                    <forms:textField id="appId" name="appId" className="longField"/>
                </td>
            </tr>
            <tr>
                <th><label for="file:fileToUpload">Private Key:</label><l:star/></th>
                <td>
                    <forms:file name="fileToUpload" size="28"/>
                    <span id="uploadError" class="error hidden"></span>
                </td>
            </tr>
            <tr>
                <th>
                    <label for="webhookSecret">WebHook secret:</label>
                </th>
                <td>
                    <forms:textField id="webhookSecret" name="webhookSecret" className="longField" value=""/>
                </td>
            </tr>
        </table>
        <input type="hidden" name="action" value="addConnection"/>
        <div class="popupSaveButtonsBlock">
            <forms:submit id="connectionsDialogSubmit" label="Save" onclick="return BS.AppGenerationDialog.generateApp()"/>
            <forms:cancel onclick="BS.ConnectionsDialog.close()"/>
        </div>
    </forms:multipartForm>
</bs:dialog>

<script type="text/javascript">
    BS.ConnectionsDialog = OO.extend(BS.AbstractWebForm, OO.extend(BS.AbstractModalDialog, OO.extend(BS.FileBrowse, {
        getContainer: function () {
            return $('connectionDialog');
        },

        deleteConnection: function(appId) {
            if (confirm('Are you sure you want to delete this connection?')) {
                BS.ajaxRequest('${action}', {
                    parameters: {
                        action: "removeConnection",
                        appId: appId
                    },
                    onComplete: function(transport) {
                        window.location.reload();
                    }
                });
            }
        },

        createConnection: function() {
            this.cleanFields();
            this.cleanErrors();
            this.showCentered();
        },

        error: function(msg) {
            this.setSaving(false);
            $j("#uploadError").show();
            $j("#uploadErrorText").text(msg);
        },

        clear: function() {
            var container = $j(this.getContainer());
            container.find("input[type=file]").val("");
            $j("#uploadError").hide();
        },

        cleanFields: function() {
            $j('#ownerUrl').val('');
            $j('#appId').val('');
            $j('#file\\:fileToUpload').val('');
        },

        cleanErrors: function() {
            $j("#uploadError").hide();
        },

        closeAndRefresh: function() {
            Form.enable($('connectionForm'));
            BS.ConnectionsDialog.close();
            window.location.reload();
        },

    })));

    BS.AppGenerationDialog = OO.extend(BS.AbstractModalDialog, {
        getContainer: function () {
            return $('generateAppConnectionFormDialog');
        },


        createConnection: function() {
            this.cleanFields();
            this.showCentered();
        },

        cleanFields: function() {
            $j('#ownerUrl').val('');
        },
    });

    BS.ConnectionsDialog.setFiles([<c:forEach var="connection" items="${connection}">'${connection.owner}',</c:forEach>]);
    BS.ConnectionsDialog.prepareFileUpload();
</script>


<style type="text/css">
  tr.githubNote table {
    width: 100%;
    border-spacing: 0;
  }

  tr.githubNote table td {
    padding: 1px 0 1px 0;
    border: none;
  }
</style>
