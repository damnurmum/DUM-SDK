# Security

DUM-SDK runs code that a server hands out, so its checks matter: signatures, the protocol, and what a client may reach on the server.

## Reporting a problem

Report privately through [Security > Report a vulnerability](https://github.com/damnurmum/DUM-SDK/security/advisories/new), not in a public issue. Describe what can be done, with which versions, and how to repeat it. You get an answer there, and the problem stays private until a fixed release is out.

Problems that belong here, for example:

- a client accepts a module, a Fabric mod or an update of the mod itself that is not signed with the release key;
- a server offers a player a module they should not get, such as an `ops-only` one, or lets a client reach the server side of a module it was not given;
- a client message can crash the server, flood it past its limits or make it write outside its folders;
- a module or a server message can make the client write outside its game folder or run code outside the module system.

Gameplay rules inside a module (rewards, cooldowns, what a module shows) are the module's and its server plugin's business; report those to the server.

## Supported versions

Only the latest release gets fixes. Since the mod and the plugin must have the same version, a fix comes as a new release of both.

## Signing keys

The release key signs the client mod and the modules players run; its public half is `Signing.RELEASE_KEY` in `dum-network`. The private key is kept off this repository and off the servers. The development key is for development builds only: players' clients refuse what it signs, and Paper leaves dev-signed modules out unless `allow-dev-signed` is on, so treat it as a test key, not a secret that protects players.
