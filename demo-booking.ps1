param(
    [Parameter(Mandatory=$true)][PSCredential]$Credential,
    [string]$Base='http://127.0.0.1:8080',
    [switch]$Register
)
$ErrorActionPreference='Stop'
$credentials=@{username=$Credential.UserName; password=$Credential.GetNetworkCredential().Password}
if ($Register) { Invoke-RestMethod "$Base/register" -Method Post -Body $credentials | Out-Null }
$login=Invoke-RestMethod "$Base/login" -Method Post -Body $credentials
$headers=@{Authorization="Bearer $($login.data.token)"}
$day=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,'China Standard Time').AddDays(1).ToString('yyyy-MM-dd')
# Look for a room covering both the original and proposed meeting.
$rooms=(Invoke-RestMethod "$Base/availability?date=$day&start=10:00&end=11:30&people=6&equipment=camera,projector" -Headers $headers).data
if (!$rooms) { throw 'No suitable room is available for the demo.' }
$request=@{room=$rooms[0].id; date=$day; start='10:00'; end='11:00'; attendees=6; equipment='camera,projector'}
$ticket=(Invoke-RestMethod "$Base/bookings" -Method Post -Headers $headers -Body $request).data
try {
    $move=@{id=$ticket.id; room=$request.room; date=$day; start='10:30'; end='11:30'; attendees=6}
    Invoke-RestMethod "$Base/reschedule" -Method Post -Headers $headers -Body $move | Out-Null
    $agenda=(Invoke-RestMethod "$Base/my-bookings?date=$day" -Headers $headers).data
    $meeting=$agenda | Where-Object { $_.id -eq $ticket.id }
    if ($meeting.start -ne '10:30' -or $meeting.attendees -ne 6) { throw 'Rescheduled booking was not found.' }
    $meeting | Select-Object id,room,start,end,attendees,status
} finally {
    Invoke-RestMethod "$Base/cancel" -Method Post -Headers $headers -Body @{id=$ticket.id} | Out-Null
}
$history=(Invoke-RestMethod "$Base/my-bookings?date=$day&includeCancelled=true" -Headers $headers).data
if (!($history | Where-Object { $_.id -eq $ticket.id -and $_.status -eq 'CANCELLED' })) { throw 'Cancellation was not recorded.' }
