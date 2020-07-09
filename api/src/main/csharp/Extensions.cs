using System;
using UuidDto = EmpowerOps.Volition.DTO.UUID;

namespace EmpowerOps.Volition.Api
{
    // exists for teamcity CI

    public static class Extensions
    {

        public static UuidDto toUuidDto(this Guid guid)
        {
            return new UuidDto() { Value = guid.ToString()};
        }

        public static Guid toGuid(this UuidDto uuid)
        {
            return Guid.Parse(uuid.Value);
        }
    }
}