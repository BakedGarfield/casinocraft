# Horse movement update

- Horses are spawned with `setNoGravity(true)` and positioned at foot height instead of centre Y + 1.
- Foot height is sampled from collision shapes immediately below the configured centre-plane; small downward steps and slabs are supported.
- An absolute `setPos` each tick bypasses walls, blocks and other entities on the programmed route; it does **not** delete or destroy obstacles.
- Entity collision responses are not completely disabled. Players may still notice overlap/pushing in some circumstances; a dedicated collision mixin or scoreboard collision setting would require additional verification.
- Horse motion is still server-position driven, so client interpolation/leg animation may not always look natural.
- Configure `/casino horse center` while standing at the track's actual surface height. The track is assumed mostly flat; large upward changes are not followed to avoid detecting walls as terrain.
- Build has not been compiled/tested in this environment; GitHub Actions must validate it.
