# Password-Free SSH from WSL to the PeeGeeQ Linux VM

## Purpose

This guide configures SSH public-key authentication from an Ubuntu WSL environment on
Windows to the PeeGeeQ Linux VM at `192.168.137.11`, whose hostname is `ubu24-cicd`.

The instructions use a dedicated Ed25519 key stored in the WSL Linux filesystem. This is
preferable to storing the key under `/mnt/c` because files in the WSL filesystem use
normal Linux ownership and permission semantics. It also keeps the WSL key independent
from any key used by Windows OpenSSH.

Replace `YOUR_LINUX_USERNAME` with the Linux account created on the VM.

## Prerequisites

- Ubuntu or another Debian-based distribution is installed under WSL.
- The VM is running with hostname `ubu24-cicd` and address `192.168.137.11`.
- The Linux account has an existing password for the initial key installation.
- WSL can route to the VM's network.
- OpenSSH Server is installed and running on the VM.

## 1. Prepare the SSH server on the VM

Use the ESXi VM console to install and start OpenSSH Server:

```bash
sudo apt update
sudo apt install -y openssh-server
sudo systemctl enable --now ssh
sudo systemctl status ssh --no-pager
```

If Ubuntu Firewall is enabled, allow SSH:

```bash
sudo ufw allow OpenSSH
sudo ufw status
```

Confirm that the server is listening on TCP port 22:

```bash
sudo ss -tlnp | grep ':22'
```

Display the VM's Ed25519 host-key fingerprint:

```bash
sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

Keep this fingerprint available. It must match the fingerprint presented to WSL during
the first connection.

## 2. Install the SSH client in WSL

Open the Ubuntu WSL terminal and install the required client packages:

```bash
sudo apt update
sudo apt install -y openssh-client netcat-openbsd
```

Confirm the required commands exist:

```bash
ssh -V
command -v ssh-keygen
command -v ssh-copy-id
```

## 3. Define the connection details

In WSL, replace the username and set these shell variables:

```bash
VM_USER="YOUR_LINUX_USERNAME"
VM_IP="192.168.137.11"
KEY="$HOME/.ssh/peegeeq_ci_ed25519"
```

These variables last for the current shell session.

Test whether WSL can reach SSH on the VM:

```bash
nc -vz "$VM_IP" 22
```

A successful result contains `succeeded`. Inspect the selected network route if the
connection fails:

```bash
ip route get "$VM_IP"
```

If port 22 is unreachable, verify the VM's IP address, ESXi virtual network, Ubuntu
firewall, SSH service, and the absence of an overlapping WSL network route before
continuing.

## 4. Make the first password-authenticated connection

Connect from WSL:

```bash
ssh "$VM_USER@$VM_IP"
```

On the first connection, SSH displays the VM's host-key fingerprint. Compare it with the
fingerprint obtained from the ESXi console. Accept the host key only when the fingerprints
match.

Enter the Linux account password. Once the login succeeds, disconnect:

```bash
exit
```

WSL stores this host decision in `~/.ssh/known_hosts`. This file is separate from the
Windows OpenSSH known-hosts file.

## 5. Generate a dedicated SSH key in WSL

Create the SSH directory with restrictive permissions:

```bash
install -d -m 700 "$HOME/.ssh"
```

Generate a dedicated Ed25519 key:

```bash
ssh-keygen \
  -t ed25519 \
  -a 100 \
  -f "$KEY" \
  -C "$(whoami)@ubu24-cicd"
```

The command asks for a key passphrase:

- For interactive use, enter a passphrase and load the key into `ssh-agent`.
- For completely unattended use, press Enter twice to leave the passphrase empty.

An empty passphrase removes the final interactive prompt, but possession of the private
key then grants access to the VM. Protect and back up the key appropriately.

The command creates:

```text
~/.ssh/peegeeq_ci_ed25519
~/.ssh/peegeeq_ci_ed25519.pub
```

The `.pub` file is the public key and may be installed on the VM. The file without
`.pub` is the private key and must never be copied to the VM, committed to Git, pasted
into a message, or shared with another user.

Confirm the local permissions and key fingerprint:

```bash
chmod 600 "$KEY"
chmod 644 "$KEY.pub"
ls -l "$KEY" "$KEY.pub"
ssh-keygen -lf "$KEY.pub"
```

## 6. Load a passphrase-protected key into the WSL SSH agent

Skip this section if the key has no passphrase.

Start an SSH agent for the current WSL session and add the private key:

```bash
eval "$(ssh-agent -s)"
ssh-add "$KEY"
ssh-add -l
```

Enter the key passphrase when requested. Subsequent SSH commands in shells that can see
this agent will not ask for the passphrase again.

The WSL SSH agent and Windows SSH agent are separate. Loading a key into the Windows
agent does not make it available to WSL. A new WSL session may require the WSL agent to
be started and the key added again.

## 7. Install the public key on the VM

Use `ssh-copy-id` from WSL:

```bash
ssh-copy-id -i "$KEY.pub" "$VM_USER@$VM_IP"
```

Enter the Linux account password. This should be the final time that password is needed
for SSH authentication.

The command appends the public key to the remote account's
`~/.ssh/authorized_keys` file and prepares the normal SSH directory structure.

## 8. Prove that key-only authentication works

If the key has a passphrase, make sure it is loaded into the WSL SSH agent. Then run:

```bash
ssh \
  -o BatchMode=yes \
  -o PasswordAuthentication=no \
  -o KbdInteractiveAuthentication=no \
  -o IdentitiesOnly=yes \
  -i "$KEY" \
  "$VM_USER@$VM_IP" \
  'printf "WSL key authentication succeeded\n"; whoami; hostname'
```

`BatchMode=yes` prevents SSH from falling back to an interactive password prompt. A
successful command proves that public-key authentication is working. The output should
contain:

- `WSL key authentication succeeded`;
- the expected Linux username; and
- the VM hostname.

Do not disable password authentication on the VM until this exact key-only test succeeds.

## 9. Create a convenient WSL SSH alias

Open the WSL SSH client configuration:

```bash
nano "$HOME/.ssh/config"
```

Add the following block, replacing the username:

```text
Host ubu24-cicd peegeeq-ci
    HostName 192.168.137.11
    User YOUR_LINUX_USERNAME
    Port 22
    IdentityFile ~/.ssh/peegeeq_ci_ed25519
    IdentitiesOnly yes
```

In Nano, save using `Ctrl+O`, press Enter, and exit using `Ctrl+X`.

Set the required permissions:

```bash
chmod 600 "$HOME/.ssh/config"
```

Connect using the alias:

```bash
ssh ubu24-cicd
```

Prove that the alias cannot fall back to a password:

```bash
ssh -o BatchMode=yes ubu24-cicd
```

Files can now be copied through the alias, for example:

```bash
scp ./example.txt ubu24-cicd:/tmp/
```

## 10. Optionally disable SSH password authentication

This hardening step is optional. Perform it only after the key-only test succeeds. Keep
one working SSH session open while changing and testing the server configuration so the
setting can be reverted if necessary.

On the VM, open a dedicated SSH configuration fragment:

```bash
sudoedit /etc/ssh/sshd_config.d/99-key-only.conf
```

Add:

```text
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
```

Validate the complete server configuration before applying it:

```bash
sudo /usr/sbin/sshd -t
```

No output means the syntax is valid. Reload SSH without terminating existing sessions:

```bash
sudo systemctl reload ssh
```

Open a separate WSL terminal and test again:

```bash
ssh -o BatchMode=yes ubu24-cicd
```

Close the original session only after the new session succeeds.

Password-free SSH does not create password-free `sudo`. SSH authentication and `sudo`
authorization are separate security controls.

## Troubleshooting

### Port 22 is unreachable

From WSL:

```bash
nc -vz 192.168.137.11 22
ip route get 192.168.137.11
```

From the VM console:

```bash
sudo systemctl status ssh --no-pager
sudo ss -tlnp | grep ':22'
sudo ufw status
```

If Windows can reach the VM but WSL cannot, investigate WSL routing, Windows firewall
policy, the VMware virtual network, and subnet overlap.

### The public key is rejected

Use the VM console or a password-authenticated session to repair the remote permissions:

```bash
install -d -m 700 "$HOME/.ssh"
touch "$HOME/.ssh/authorized_keys"
chmod 600 "$HOME/.ssh/authorized_keys"
chown -R "$USER:$USER" "$HOME/.ssh"
```

Review the SSH service log on the VM:

```bash
sudo journalctl -u ssh --since "10 minutes ago"
```

Enable detailed client diagnostics in WSL:

```bash
ssh -vvv -i "$KEY" "$VM_USER@$VM_IP"
```

### A passphrase-protected key is not being selected

Check the WSL agent:

```bash
ssh-add -l
```

If no keys are listed:

```bash
eval "$(ssh-agent -s)"
ssh-add "$KEY"
```

Then repeat the key-only test.

### SSH reports that the host key changed

Do not remove the saved host key automatically. Use the ESXi console to verify whether
the VM was rebuilt or its SSH host keys were intentionally regenerated. Compare the
current fingerprint:

```bash
sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

Only after verifying the change, remove the obsolete entry from WSL:

```bash
ssh-keygen -R 192.168.137.11
```

Reconnect and verify the new fingerprint before accepting it.

## Security notes

- Keep the private key in the WSL filesystem under `~/.ssh`, not under `/mnt/c`.
- Never commit the private or public key to the PeeGeeQ repository.
- Use a dedicated key for this VM so it can be revoked without affecting other systems.
- Prefer a passphrase-protected key with `ssh-agent` for interactive administration.
- Use an unencrypted key only when unattended automation genuinely requires it.
- Restrict SSH access to trusted networks, a management VLAN, or a VPN.
- Disable password authentication only after key-only login has been proven in a second
  session.

## References

- Ubuntu OpenSSH Server: <https://ubuntu.com/server/docs/how-to/security/openssh-server/>
- Microsoft WSL file permissions: <https://learn.microsoft.com/windows/wsl/file-permissions>
- Microsoft WSL filesystem guidance: <https://learn.microsoft.com/windows/wsl/filesystems>
