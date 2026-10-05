# Espobase for Kotlin (JVM and Android)

The Espobase client for Kotlin: your project's database, App Authentication (including Sign in with Apple), Storage and
edge functions, with coroutines. It is a JVM library (Java 11+), so it works in Android apps (minSdk 26) and server-side
Kotlin. Dependencies: kotlinx.coroutines, kotlinx.serialization and OkHttp.

> Status: version 0.1.0, not published yet (owner to publish to Maven Central or a repository of their choice). Realtime
> (WebSockets) is not in this version: use the REST API, or the JavaScript client for realtime.

## Install

```kotlin
dependencies {
    implementation("com.espobase:espobase-kotlin:0.1.0")
}
plugins { kotlin("plugin.serialization") }   // your own @Serializable row types
```

## Create a client

```kotlin
val espobase = EspobaseClient(
    "https://api.espobase.com/project/<your project ref>",
    "eb_pk_…", // the PUBLISHABLE key: safe in an app
)
```

Never put a secret key (`eb_sk_…`) in an app: `EspobaseClient` refuses it unless `ClientOptions(serverSide = true)` (for a
trusted server).

## Database

```kotlin
@Serializable data class Todo(val id: Int, val title: String, val done: Boolean)

val todos: List<Todo> = espobase.from("todos").select().eq("done", false).order("created_at", ascending = false).limit(20).execute()

espobase.from("todos").insert(buildJsonObject { put("title", "Buy milk") }).run()
espobase.from("todos").update(buildJsonObject { put("done", true) }).eq("id", 1).run()
espobase.from("todos").delete().eq("id", 1).run()
val one: Todo = espobase.from("todos").select().eq("id", 1).single()
```

`update()` and `delete()` refuse to run without a filter. Filters: `eq neq gt gte lt lte like ilike inList isNull isBool`;
`order`, `limit`, `range`, `single()`. Every method returns a new query, so a base query can be reused. Database functions:
`espobase.rpcAs<List<Match>>("match_documents", params)`. Row level security applies to everything, as the signed-in user.

## Sign-up and sign-in

```kotlin
espobase.auth.signUp("a@b.co", "password", redirectTo = "myapp://auth/callback")
val session = espobase.auth.signInWithPassword("a@b.co", "password")

espobase.auth.authStateChanges.collect { change -> /* change.event, change.session */ }
```

By default the session lives in memory. To stay signed in across launches, give the client a `SessionStorage` backed by
`EncryptedSharedPreferences`. Add your app link (`myapp://auth/callback`) under Authentication › Settings › Allowed redirect
URLs for confirmation and password-reset emails.

### Sign in with Apple, Google, GitHub

Turn the provider on under Authentication › Providers. Social sign-in opens a web page and comes back with a code:

```kotlin
val url = espobase.auth.signInWithOAuth(OAuthProvider.google, redirectTo = "myapp://auth/callback")
// open `url` in a Custom Tab; your activity receives myapp://auth/callback?code=…
val session = espobase.auth.exchangeCodeForSession(code)
```

Only this device can finish the sign-in (PKCE). With an Apple identity token from your own flow:
`espobase.auth.signInWithIdToken(token, AppleNonce().raw)` (give Apple `nonce.hashed`). A sign-in that needs a second step
throws `mfa_required`; finish it with `espobase.auth.mfa.verify(code)`.

## Storage

```kotlin
val photos = espobase.storage.from("photos")
photos.upload("$userId/me.jpg", bytes, "image/jpeg")
val data = photos.download("$userId/me.jpg")
val thumb = photos.getPublicUrl("logo.png", ImageTransform(width = 200)) // Pro and Group
val link = photos.createSignedUrl("$userId/me.jpg", 600)
```

## Edge functions

```kotlin
val answer = espobase.functions.invoke("hello", buildJsonObject { put("name", "Ada") })
println(answer.json())
```

## Errors

Everything throws `EspobaseException` (`status`, `code`, `message`, `details`, `hint`); `status` is 0 for network and
client-side errors.

## Tests

`./gradlew test` (24 tests with a mock `HttpTransport`: no network; needs JDK 17 and downloads Gradle and the libraries once).
