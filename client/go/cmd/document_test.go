// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// document command tests
// Author: bratseth

package cmd

import (
	"io/ioutil"
	"strconv"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/vespa-engine/vespa/util"
	"github.com/vespa-engine/vespa/vespa"
)

func TestDocumentPostWithIdArg(t *testing.T) {
	assertDocumentPost([]string{"document", "post", "id:mynamespace:music::a-head-full-of-dreams", "testdata/A-Head-Full-of-Dreams-Without-Id.json"},
		"id:mynamespace:music::a-head-full-of-dreams", "testdata/A-Head-Full-of-Dreams-Without-Id.json", t)
}

func TestDocumentPostWithIdInDocument(t *testing.T) {
	assertDocumentPost([]string{"document", "post", "testdata/A-Head-Full-of-Dreams.json"},
		"id:mynamespace:music::a-head-full-of-dreams", "testdata/A-Head-Full-of-Dreams.json", t)
}

func TestDocumentPostWithIdInDocumentShortForm(t *testing.T) {
	assertDocumentPost([]string{"document", "testdata/A-Head-Full-of-Dreams.json"},
		"id:mynamespace:music::a-head-full-of-dreams", "testdata/A-Head-Full-of-Dreams.json", t)
}

func TestDocumentPostIdNotSpecified(t *testing.T) {
	arguments := []string{"document", "post", "testdata/A-Head-Full-of-Dreams-Without-Id.json"}
	client := &mockHttpClient{}
	assert.Equal(t,
		"Error: No document id given neither as argument or as a 'put' key in the json file\n",
		executeCommand(t, client, arguments, []string{}))
}

func TestDocumentPostDocumentError(t *testing.T) {
	assertDocumentError(t, 401, "Document error")
}

func TestDocumentPostServerError(t *testing.T) {
	assertDocumentServerError(t, 501, "Server error")
}

func TestDocumentGet(t *testing.T) {
	assertDocumentGet([]string{"document", "get", "id:mynamespace:music::a-head-full-of-dreams"},
		"id:mynamespace:music::a-head-full-of-dreams", t)
}

func assertDocumentPost(arguments []string, documentId string, jsonFile string, t *testing.T) {
	client := &mockHttpClient{}
	assert.Equal(t,
		"Success: Wrote "+documentId+"\n",
		executeCommand(t, client, arguments, []string{}))
	target := getTarget(documentContext).document
	expectedPath, _ := vespa.IdToURLPath(documentId)
	assert.Equal(t, target+"/document/v1/"+expectedPath, client.lastRequest.URL.String())
	assert.Equal(t, "application/json", client.lastRequest.Header.Get("Content-Type"))
	assert.Equal(t, "POST", client.lastRequest.Method)

	fileContent, _ := ioutil.ReadFile(jsonFile)
	assert.Equal(t, string(fileContent), util.ReaderToString(client.lastRequest.Body))
}

func assertDocumentPostShortForm(documentId string, jsonFile string, t *testing.T) {
	client := &mockHttpClient{}
	assert.Equal(t,
		"Success\n",
		executeCommand(t, client, []string{"document", jsonFile}, []string{}))
	target := getTarget(documentContext).document
	assert.Equal(t, target+"/document/v1/"+documentId, client.lastRequest.URL.String())
}

func assertDocumentGet(arguments []string, documentId string, t *testing.T) {
	client := &mockHttpClient{
		nextBody: "{\"fields\":{\"foo\":\"bar\"}}",
	}
	assert.Equal(t,
		`{
    "fields": {
        "foo": "bar"
    }
}
`,
		executeCommand(t, client, arguments, []string{}))
	target := getTarget(documentContext).document
	expectedPath, _ := vespa.IdToURLPath(documentId)
	assert.Equal(t, target+"/document/v1/"+expectedPath, client.lastRequest.URL.String())
	assert.Equal(t, "GET", client.lastRequest.Method)
}

func assertDocumentError(t *testing.T, status int, errorMessage string) {
	client := &mockHttpClient{nextStatus: status, nextBody: errorMessage}
	assert.Equal(t,
		"Error: Invalid document: Status "+strconv.Itoa(status)+"\n\n"+errorMessage+"\n",
		executeCommand(t, client, []string{"document", "post",
			"id:mynamespace:music::a-head-full-of-dreams",
			"testdata/A-Head-Full-of-Dreams.json"}, []string{}))
}

func assertDocumentServerError(t *testing.T, status int, errorMessage string) {
	client := &mockHttpClient{nextStatus: status, nextBody: errorMessage}
	assert.Equal(t,
		"Error: Container (document API) at 127.0.0.1:8080: Status "+strconv.Itoa(status)+"\n\n"+errorMessage+"\n",
		executeCommand(t, client, []string{"document", "post",
			"id:mynamespace:music::a-head-full-of-dreams",
			"testdata/A-Head-Full-of-Dreams.json"}, []string{}))
}
