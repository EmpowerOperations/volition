using EmpowerOps.Volition.Dto;

namespace EmpowerOps.Volition.Api
{
    // exists for teamcity CI

    public static class Extensions
    {

        public static RunIDDTO toUuidDto(this Guid guid)
        {
            return new RunIDDTO() { Value = guid.ToString()};
        }

        public static Guid toGuid(this RunIDDTO uuid)
        {
            return Guid.Parse(uuid.Value);
        }
    }
}