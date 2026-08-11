'use strict';

const form = document.querySelector('#relay-form');
const input = document.querySelector('#relay-url');
const submit = document.querySelector('#submit');
const cancel = document.querySelector('#cancel');
const errorMessage = document.querySelector('#error-message');

function setBusy(busy) {
  input.disabled = busy;
  submit.disabled = busy;
  cancel.disabled = busy;
  submit.textContent = busy ? 'Connecting…' : 'Open Remotex';
}

function showError(message) {
  errorMessage.textContent = message || '';
  errorMessage.hidden = !message;
}

async function initialize() {
  try {
    const state = await window.remotexSetup.getState();
    if (state.relayUrl) {
      input.value = state.relayUrl;
    }
    cancel.hidden = !state.canCancel;
    input.focus();
    input.select();
  } catch {
    showError('Desktop settings could not be loaded. Restart Remotex and try again.');
  }
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  showError('');
  setBusy(true);

  try {
    const result = await window.remotexSetup.saveRelayUrl(input.value);
    if (!result.ok) {
      showError(result.error || 'That relay URL cannot be used.');
      setBusy(false);
    }
  } catch {
    showError('Desktop settings could not be saved. Check the URL and try again.');
    setBusy(false);
  }
});

cancel.addEventListener('click', async () => {
  setBusy(true);
  try {
    await window.remotexSetup.cancel();
  } catch {
    setBusy(false);
  }
});

initialize();
